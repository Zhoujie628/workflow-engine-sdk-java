/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.spring;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;

import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Keeps the A2A request handler's CDI-only injection points outside Spring bean processing.
 *
 * <p>A2A Java SDK 1.2 supports both builder-created handlers and CDI-managed handlers. Its concrete
 * {@code DefaultRequestHandler} therefore contains optional CDI {@code Instance} fields. Spring
 * treats those fields as mandatory injection points when the concrete object itself is registered
 * as a bean. This typed delegate exposes only the public {@link RequestHandler} contract to Spring;
 * the builder-created delegate remains framework-neutral.
 */
final class SpringCompatibleRequestHandler implements RequestHandler {
    private final RequestHandler delegate;

    SpringCompatibleRequestHandler(RequestHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Task onGetTask(TaskQueryParams params, ServerCallContext context) throws A2AError {
        return delegate.onGetTask(params, context);
    }

    @Override
    public ListTasksResult onListTasks(ListTasksParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onListTasks(params, context);
    }

    @Override
    public Task onCancelTask(CancelTaskParams params, ServerCallContext context) throws A2AError {
        return delegate.onCancelTask(params, context);
    }

    @Override
    public EventKind onMessageSend(MessageSendParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onMessageSend(params, context);
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onMessageSendStream(
            MessageSendParams params, ServerCallContext context) throws A2AError {
        return delegate.onMessageSendStream(params, context);
    }

    @Override
    public TaskPushNotificationConfig onCreateTaskPushNotificationConfig(
            TaskPushNotificationConfig config, ServerCallContext context) throws A2AError {
        return delegate.onCreateTaskPushNotificationConfig(config, context);
    }

    @Override
    public TaskPushNotificationConfig onGetTaskPushNotificationConfig(
            GetTaskPushNotificationConfigParams params, ServerCallContext context) throws A2AError {
        return delegate.onGetTaskPushNotificationConfig(params, context);
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onSubscribeToTask(
            TaskIdParams params, ServerCallContext context) throws A2AError {
        return delegate.onSubscribeToTask(params, context);
    }

    @Override
    public ListTaskPushNotificationConfigsResult onListTaskPushNotificationConfigs(
            ListTaskPushNotificationConfigsParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onListTaskPushNotificationConfigs(params, context);
    }

    @Override
    public void onDeleteTaskPushNotificationConfig(
            DeleteTaskPushNotificationConfigParams params, ServerCallContext context)
            throws A2AError {
        delegate.onDeleteTaskPushNotificationConfig(params, context);
    }

    @Override
    public void authorizeTaskAccess(
            String taskId, ServerCallContext context, TaskOperation operation) throws A2AError {
        delegate.authorizeTaskAccess(taskId, context, operation);
    }
}
