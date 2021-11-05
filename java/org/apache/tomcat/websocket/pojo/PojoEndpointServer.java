/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.websocket.pojo;

import java.util.Map;

import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpointConfig;

import org.apache.tomcat.util.res.StringManager;

/**
 * Wrapper class for instances of POJOs annotated with
 * {@link javax.websocket.server.ServerEndpoint} so they appear as standard
 * {@link javax.websocket.Endpoint} instances.
 */
public class PojoEndpointServer extends PojoEndpointBase {

    private static final StringManager sm =
            StringManager.getManager(PojoEndpointServer.class);
    /**
     * 以ws 协议举例：
     * 会进入这个方法，这个方法会一步一步 执行到，我们所写业务的onOpen 方法
     */
    @Override
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        //处理配置文件
        ServerEndpointConfig sec = (ServerEndpointConfig) endpointConfig;
        //这里 要区分 方法和对象，如：我要执行某个对象的方法
        Object pojo;
        try {
            //获取 要真正执行的onOpen方法的对象
            pojo = sec.getConfigurator().getEndpointInstance(
                    sec.getEndpointClass());
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(sm.getString(
                    "pojoEndpointServer.getPojoInstanceFail",
                    sec.getEndpointClass().getName()), e);
        }
        //设置 要执行 onOpen的对象
        setPojo(pojo);

        @SuppressWarnings("unchecked")
        Map<String,String> pathParameters =
                (Map<String, String>) sec.getUserProperties().get(
                        Constants.POJO_PATH_PARAM_KEY);
        setPathParameters(pathParameters);
        //获取 要真正执行的onOpen方法
        PojoMethodMapping methodMapping =
                (PojoMethodMapping) sec.getUserProperties().get(
                        Constants.POJO_METHOD_MAPPING_KEY);
        //设置要真正执行的方法
        setMethodMapping(methodMapping);
        //这个方法里面 ，会执行反射，让真正的onOpen方法执行
        doOnOpen(session, endpointConfig);
    }
}
