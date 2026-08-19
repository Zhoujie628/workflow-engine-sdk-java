/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

class ExtensionSenderTest {

    @Test
    void notificationCallbackMethodIsAnInterfaceContract() throws Exception {
        Method method =
                ExtensionSender.class.getMethod(
                        "sendNotification",
                        String.class,
                        String.class,
                        String.class,
                        java.util.function.Consumer.class);

        assertTrue(Modifier.isAbstract(method.getModifiers()));
    }
}
