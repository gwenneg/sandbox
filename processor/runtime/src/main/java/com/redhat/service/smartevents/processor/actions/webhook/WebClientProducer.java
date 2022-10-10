package com.redhat.service.smartevents.processor.actions.webhook;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;

public class WebClientProducer {

    @Inject
    Vertx vertx;

    @Singleton
    @SslVerificationEnabled
    public WebClient produceSecuredWebClient() {
        WebClientOptions options = buildOptions();
        return WebClient.create(vertx, options);
    }

    @Singleton
    @SslVerificationDisabled
    public WebClient produceUnsecuredWebClient() {
        WebClientOptions options = buildOptions()
                .setTrustAll(true)
                .setVerifyHost(false);
        return WebClient.create(vertx, options);
    }

    private static WebClientOptions buildOptions() {
        return new WebClientOptions().setLogActivity(true);
    }
}
