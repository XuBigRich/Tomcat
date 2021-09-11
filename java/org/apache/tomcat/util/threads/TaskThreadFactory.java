/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.threads;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tomcat.util.security.PrivilegedSetTccl;

/**
 * Simple task thread factory to use to create threads for an executor
 * implementation.
 * 简单的线程工厂使使用他创建线程执行， 继承了ThreadFactory，一般情况下 线程池会对线程工厂的newThread方法进行调用
 */
public class TaskThreadFactory implements ThreadFactory {

    private final ThreadGroup group;
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;
    private final boolean daemon;
    private final int threadPriority;

    /**
     * 任务线程工厂的初始化方法，传入名字前缀，是否是守护线程，线程优先级
     *
     * @param namePrefix
     * @param daemon
     * @param priority
     */
    public TaskThreadFactory(String namePrefix, boolean daemon, int priority) {
        SecurityManager s = System.getSecurityManager();
        group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        this.namePrefix = namePrefix;
        this.daemon = daemon;
        this.threadPriority = priority;
    }

    @Override
    public Thread newThread(Runnable r) {
        //TaskThread是一个线程的包装类,创建一个线程，传入线程组，名称前缀+线程序号
        TaskThread t = new TaskThread(group, r, namePrefix + threadNumber.getAndIncrement());
        t.setDaemon(daemon);
        t.setPriority(threadPriority);

        // Set the context class loader of newly created threads to be the class
        // loader that loaded this factory. This avoids retaining references to
        // web application class loaders and similar.
        //判断是否是安全模式启动 ，这么做的意义就是设置线程的上下文加载器
        if (Constants.IS_SECURITY_ENABLED) {
            PrivilegedAction<Void> pa = new PrivilegedSetTccl(
                    t, getClass().getClassLoader());
            AccessController.doPrivileged(pa);
        } else {
            //设置线程上下文加载器为t
            t.setContextClassLoader(getClass().getClassLoader());
        }

        return t;
    }
}
