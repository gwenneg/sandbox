package com.redhat.service.smartevents.manager.workers.resources;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.openshift.cloud.api.connector.models.Connector;
import com.openshift.cloud.api.connector.models.ConnectorState;
import com.openshift.cloud.api.connector.models.ConnectorStatusStatus;
import com.redhat.service.smartevents.infra.exceptions.definitions.platform.InternalPlatformException;
import com.redhat.service.smartevents.infra.models.dto.ManagedResourceStatus;
import com.redhat.service.smartevents.manager.RhoasService;
import com.redhat.service.smartevents.manager.connectors.ConnectorsApiClient;
import com.redhat.service.smartevents.manager.dao.BridgeDAO;
import com.redhat.service.smartevents.manager.dao.ConnectorsDAO;
import com.redhat.service.smartevents.manager.dao.ProcessorDAO;
import com.redhat.service.smartevents.manager.models.Bridge;
import com.redhat.service.smartevents.manager.models.ConnectorEntity;
import com.redhat.service.smartevents.manager.models.Processor;
import com.redhat.service.smartevents.manager.utils.DatabaseManagerUtils;
import com.redhat.service.smartevents.manager.utils.Fixtures;
import com.redhat.service.smartevents.manager.workers.Work;
import com.redhat.service.smartevents.manager.workers.WorkManager;
import com.redhat.service.smartevents.rhoas.RhoasTopicAccessType;
import com.redhat.service.smartevents.test.resource.PostgresResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

import static com.redhat.service.smartevents.manager.workers.resources.WorkerTestUtils.makeWork;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class ConnectorWorkerTest {

    private static final String TEST_CONNECTOR_EXTERNAL_ID = "connectorExternalId";
    private static final String TEST_RESOURCE_ID = "123";

    @InjectMock
    RhoasService rhoasServiceMock;

    @InjectMock
    ConnectorsApiClient connectorsApiMock;

    @InjectMock
    WorkManager workManagerMock;

    @Inject
    ConnectorWorker worker;

    @Inject
    BridgeDAO bridgeDAO;

    @Inject
    ProcessorDAO processorDAO;

    @Inject
    ConnectorsDAO connectorsDAO;

    @Inject
    DatabaseManagerUtils databaseManagerUtils;

    @BeforeEach
    public void setup() {
        databaseManagerUtils.cleanUpAndInitWithDefaultShard();
    }

    @Test
    void handleWorkProvisioningWithUnknownResource() {
        Work work = makeWork(TEST_RESOURCE_ID, 0, ZonedDateTime.now(ZoneOffset.UTC));

        assertThatCode(() -> worker.handleWork(work)).isInstanceOf(IllegalStateException.class);
    }

    @Transactional
    @ParameterizedTest
    @MethodSource("provideArgsForCreateTest")
    void handleWorkProvisioningWithKnownResourceMultiplePasses(
            ManagedResourceStatus resourceStatus,
            ConnectorState connectorState,
            boolean throwRhoasError,
            boolean useSourceConnectorEntity,
            RhoasTopicAccessType expectedTopicAccessType,
            ManagedResourceStatus expectedResourceStatus) {
        Bridge bridge = Fixtures.createBridge();
        Processor processor = Fixtures.createProcessor(bridge, ManagedResourceStatus.READY);
        ConnectorEntity connectorEntity = useSourceConnectorEntity
                ? Fixtures.createSourceConnector(processor, resourceStatus)
                : Fixtures.createSinkConnector(processor, resourceStatus);
        connectorEntity.setPublishedAt(null);//The publishedAt date is set by the Worker so reset that set by the Fixture
        bridgeDAO.persist(bridge);
        processorDAO.persist(processor);
        connectorsDAO.persist(connectorEntity);

        // ConnectorWorker accepts the Processor Id and looks up the applicable Connector
        Work work = makeWork(processor.getId(), 0, ZonedDateTime.now(ZoneOffset.UTC));

        Connector connector = new Connector();
        connector.setId(TEST_CONNECTOR_EXTERNAL_ID);
        connector.setStatus(new ConnectorStatusStatus().state(connectorState));
        connector.setConnector(new TextNode("definition"));

        when(connectorsApiMock.getConnector(TEST_CONNECTOR_EXTERNAL_ID)).thenReturn(null, connector);
        when(connectorsApiMock.createConnector(connectorEntity)).thenReturn(connector);

        if (throwRhoasError) {
            when(rhoasServiceMock.createTopicAndGrantAccessFor(any(), any())).thenThrow(new InternalPlatformException("error"));
        }

        ConnectorEntity refreshed = worker.handleWork(work);

        verify(rhoasServiceMock).createTopicAndGrantAccessFor(connectorEntity.getTopicName(), expectedTopicAccessType);
        verify(connectorsApiMock, times(throwRhoasError ? 0 : 1)).createConnector(connectorEntity);
        assertThat(refreshed.getStatus()).isEqualTo(ManagedResourceStatus.PREPARING);
        assertThat(refreshed.getDependencyStatus()).isEqualTo(ManagedResourceStatus.PROVISIONING);
        verify(workManagerMock, never()).reschedule(work);

        // This emulates a subsequent invocation from the ProcessorWorker
        refreshed = worker.handleWork(work);

        verify(rhoasServiceMock, times(2)).createTopicAndGrantAccessFor(connectorEntity.getTopicName(), expectedTopicAccessType);
        verify(connectorsApiMock, times(throwRhoasError ? 0 : 1)).createConnector(connectorEntity);

        assertThat(refreshed.getStatus()).isEqualTo(throwRhoasError ? ManagedResourceStatus.PREPARING : expectedResourceStatus);
        assertThat(refreshed.getDependencyStatus()).isEqualTo(throwRhoasError ? ManagedResourceStatus.PROVISIONING : expectedResourceStatus);
        if (expectedResourceStatus == ManagedResourceStatus.READY) {
            assertThat(refreshed.getPublishedAt()).isNotNull();
        } else {
            assertThat(refreshed.getPublishedAt()).isNull();
        }
        if (expectedResourceStatus == ManagedResourceStatus.FAILED) {
            assertThat(refreshed.getErrorId()).isNotNull();
            assertThat(refreshed.getErrorUUID()).isNotNull();
        }
        verify(workManagerMock, never()).reschedule(work);

        assertThat(processor.getErrorId()).isNull();
        assertThat(processor.getErrorUUID()).isNull();
    }

    @Transactional
    @ParameterizedTest
    @MethodSource("provideArgsForUpdateTest")
    void handleWorkUpdatingWithKnownResource(boolean useSourceConnectorEntity, JsonNode updatedDefinition, boolean patchConnector) {
        Bridge bridge = Fixtures.createBridge();
        Processor processor = Fixtures.createProcessor(bridge, ManagedResourceStatus.READY);
        processor.setGeneration(patchConnector ? 1 : 0);

        ConnectorEntity connectorEntity = useSourceConnectorEntity
                ? Fixtures.createSourceConnector(processor, ManagedResourceStatus.ACCEPTED)
                : Fixtures.createSinkConnector(processor, ManagedResourceStatus.ACCEPTED);
        connectorEntity.setPublishedAt(null);

        bridgeDAO.persist(bridge);
        processorDAO.persist(processor);
        connectorsDAO.persist(connectorEntity);

        // Set-up ManagedConnector to match ConnectorEntity so subsequent update is detected
        Connector connector = new Connector();
        connector.setId(TEST_CONNECTOR_EXTERNAL_ID);
        connector.setStatus(new ConnectorStatusStatus().state(ConnectorState.READY));
        connector.setConnector(connectorEntity.getDefinition());

        // Update ConnectorEntity with new definition
        connectorEntity.setDefinition(updatedDefinition);

        // ConnectorWorker accepts the Processor Id and looks up the applicable Connector
        Work work = makeWork(processor.getId(), 0, ZonedDateTime.now(ZoneOffset.UTC));

        when(connectorsApiMock.getConnector(TEST_CONNECTOR_EXTERNAL_ID)).thenReturn(connector);

        ConnectorEntity refreshed = worker.handleWork(work);

        if (patchConnector) {
            verify(connectorsApiMock).updateConnector(connectorEntity.getConnectorExternalId(), updatedDefinition);
        } else {
            assertThat(refreshed.getStatus()).isEqualTo(ManagedResourceStatus.READY);
            assertThat(refreshed.getDependencyStatus()).isEqualTo(ManagedResourceStatus.READY);
        }

        verify(workManagerMock, never()).reschedule(work);
    }

    @Transactional
    @ParameterizedTest
    @MethodSource("provideArgsForDeleteTest")
    void handleWorkDeletingWithKnownResourceMultiplePasses(
            ManagedResourceStatus resourceStatus,
            ConnectorState connectorState,
            boolean useSourceConnectorEntity,
            RhoasTopicAccessType expectedTopicAccessType) {
        Bridge bridge = Fixtures.createBridge();
        Processor processor = Fixtures.createProcessor(bridge, ManagedResourceStatus.READY);
        ConnectorEntity connectorEntity = useSourceConnectorEntity
                ? Fixtures.createSourceConnector(processor, resourceStatus)
                : Fixtures.createSinkConnector(processor, resourceStatus);
        connectorEntity.setPublishedAt(null);//The publishedAt date is set by the Worker so reset that set by the Fixture
        bridgeDAO.persist(bridge);
        processorDAO.persist(processor);
        connectorsDAO.persist(connectorEntity);

        // ConnectorWorker accepts the Processor Id and looks up the applicable Connector
        Work work = makeWork(processor.getId(), 0, ZonedDateTime.now(ZoneOffset.UTC));

        Connector connector = new Connector();
        connector.setStatus(new ConnectorStatusStatus().state(connectorState));

        // Managed Connector will initially be available before it is deleted
        when(connectorsApiMock.getConnector(connectorEntity.getConnectorExternalId())).thenReturn(connector, (Connector) null);

        ConnectorEntity refreshed = worker.handleWork(work);

        if (connectorState != ConnectorState.DELETED) {
            assertThat(refreshed.getStatus()).isEqualTo(ManagedResourceStatus.DELETING);
            assertThat(refreshed.getDependencyStatus()).isEqualTo(ManagedResourceStatus.DELETING);
            verify(rhoasServiceMock, never()).deleteTopicAndRevokeAccessFor(connectorEntity.getTopicName(), RhoasTopicAccessType.PRODUCER);
            verify(connectorsApiMock).deleteConnector(connectorEntity.getConnectorExternalId());

            // This emulates a subsequent invocation by WorkManager from the ProcessorWorker
            refreshed = worker.handleWork(work);
        }

        verify(rhoasServiceMock).deleteTopicAndRevokeAccessFor(connectorEntity.getTopicName(), expectedTopicAccessType);
        assertThat(connectorsDAO.findById(connectorEntity.getId())).isNull();

        assertThat(refreshed.getStatus()).isEqualTo(ManagedResourceStatus.DELETED);
        assertThat(refreshed.getDependencyStatus()).isEqualTo(ManagedResourceStatus.DELETED);
        verify(workManagerMock, never()).reschedule(work);
    }

    private static Stream<Arguments> provideArgsForCreateTest() {
        Object[][] arguments = {
                { ManagedResourceStatus.ACCEPTED, ConnectorState.READY, false, true, RhoasTopicAccessType.CONSUMER, ManagedResourceStatus.READY },
                { ManagedResourceStatus.ACCEPTED, ConnectorState.FAILED, false, true, RhoasTopicAccessType.CONSUMER, ManagedResourceStatus.FAILED },
                { ManagedResourceStatus.PREPARING, ConnectorState.READY, false, true, RhoasTopicAccessType.CONSUMER, ManagedResourceStatus.READY },
                { ManagedResourceStatus.PREPARING, ConnectorState.FAILED, false, true, RhoasTopicAccessType.CONSUMER, ManagedResourceStatus.FAILED },
                { ManagedResourceStatus.ACCEPTED, ConnectorState.READY, false, false, RhoasTopicAccessType.PRODUCER, ManagedResourceStatus.READY },
                { ManagedResourceStatus.ACCEPTED, ConnectorState.FAILED, false, false, RhoasTopicAccessType.PRODUCER, ManagedResourceStatus.FAILED },
                { ManagedResourceStatus.PREPARING, ConnectorState.READY, false, false, RhoasTopicAccessType.PRODUCER, ManagedResourceStatus.READY },
                { ManagedResourceStatus.PREPARING, ConnectorState.FAILED, false, false, RhoasTopicAccessType.PRODUCER, ManagedResourceStatus.FAILED },

                { ManagedResourceStatus.ACCEPTED, null, true, true, RhoasTopicAccessType.CONSUMER, ManagedResourceStatus.PROVISIONING },
                { ManagedResourceStatus.PREPARING, null, true, true, RhoasTopicAccessType.CONSUMER, ManagedResourceStatus.PROVISIONING },
                { ManagedResourceStatus.ACCEPTED, null, true, false, RhoasTopicAccessType.PRODUCER, ManagedResourceStatus.PROVISIONING },
                { ManagedResourceStatus.PREPARING, null, true, false, RhoasTopicAccessType.PRODUCER, ManagedResourceStatus.PROVISIONING }
        };
        return Stream.of(arguments).map(Arguments::of);
    }

    private static Stream<Arguments> provideArgsForDeleteTest() {
        Object[][] arguments = {
                { ManagedResourceStatus.DEPROVISION, ConnectorState.READY, true, RhoasTopicAccessType.CONSUMER },
                { ManagedResourceStatus.DEPROVISION, ConnectorState.FAILED, true, RhoasTopicAccessType.CONSUMER },
                { ManagedResourceStatus.DEPROVISION, ConnectorState.DELETED, true, RhoasTopicAccessType.CONSUMER },
                { ManagedResourceStatus.DELETING, ConnectorState.READY, true, RhoasTopicAccessType.CONSUMER },
                { ManagedResourceStatus.DELETING, ConnectorState.FAILED, true, RhoasTopicAccessType.CONSUMER },
                { ManagedResourceStatus.DELETING, ConnectorState.DELETED, true, RhoasTopicAccessType.CONSUMER },
                { ManagedResourceStatus.DEPROVISION, ConnectorState.READY, false, RhoasTopicAccessType.PRODUCER },
                { ManagedResourceStatus.DEPROVISION, ConnectorState.FAILED, false, RhoasTopicAccessType.PRODUCER },
                { ManagedResourceStatus.DEPROVISION, ConnectorState.DELETED, false, RhoasTopicAccessType.PRODUCER },
                { ManagedResourceStatus.DELETING, ConnectorState.READY, false, RhoasTopicAccessType.PRODUCER },
                { ManagedResourceStatus.DELETING, ConnectorState.FAILED, false, RhoasTopicAccessType.PRODUCER },
                { ManagedResourceStatus.DELETING, ConnectorState.DELETED, false, RhoasTopicAccessType.PRODUCER }
        };
        return Stream.of(arguments).map(Arguments::of);
    }

    private static Stream<Arguments> provideArgsForUpdateTest() {
        Object[][] arguments = {
                { true, new TextNode("definition"), false },
                { true, new TextNode("definition-updated"), true }
        };
        return Stream.of(arguments).map(Arguments::of);
    }

}
