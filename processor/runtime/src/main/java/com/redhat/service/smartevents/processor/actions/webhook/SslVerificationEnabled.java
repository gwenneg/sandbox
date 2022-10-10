package com.redhat.service.smartevents.processor.actions.webhook;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Use this qualifier to inject a {@link io.vertx.mutiny.ext.web.client.WebClient WebClient} instance that will verify SSL server certificates and the hostname.
 */
@Qualifier
@Retention(RUNTIME)
@Target({ METHOD, FIELD })
public @interface SslVerificationEnabled {
}
