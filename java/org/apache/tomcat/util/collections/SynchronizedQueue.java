/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.collections;

/**
 * This is intended as a (mostly) GC-free alternative to
 * {@link java.util.concurrent.ConcurrentLinkedQueue} when the requirement is to
 * create an unbounded queue with no requirement to shrink the queue. The aim is
 * to provide the bare minimum of required functionality as quickly as possible
 * with minimum garbage.
 *
 * @param <T> The type of object managed by this queue
 */
public class SynchronizedQueue<T> {

    public static final int DEFAULT_SIZE = 128;

    private Object[] queue;
    private int size;
    private int insert = 0;
    private int remove = 0;

    public SynchronizedQueue() {
        this(DEFAULT_SIZE);
    }

    //初始化队列与容量大小
    public SynchronizedQueue(int initialSize) {
        queue = new Object[initialSize];
        size = initialSize;
    }

    public synchronized boolean offer(T t) {
        //放入一个元素
        queue[insert++] = t;

        // Wrap
        //判断元素是否等于容量大小，如果等于容量大小，那么insert变为0（下次offer将覆盖第一个）
        if (insert == size) {
            insert = 0;
        }
        //如果insert与remove 插入的与读到的相等了
        if (insert == remove) {
            //扩容
            expand();
        }
        return true;
    }

    public synchronized T poll() {
        //如果inser等于remove
        if (insert == remove) {
            // empty
            return null;
        }
        //remove一开始为0，当发生一次poll时，会取出当前remove指向的元素
        @SuppressWarnings("unchecked")
        T result = (T) queue[remove];
        //将数组remove指向的元素 置为null （出栈）
        queue[remove] = null;
        //元素读取指针+1
        remove++;

        // Wrap  如果读到投了
        if (remove == size) {
            //让他从零开始重新读
            remove = 0;
        }

        return result;
    }

    //给容器容量扩容两倍
    private void expand() {
        int newSize = size * 2;
        //新建立一个数组
        Object[] newQueue = new Object[newSize];
        //将老数组元素复制到新数组中
        System.arraycopy(queue, insert, newQueue, 0, size - insert);
        System.arraycopy(queue, 0, newQueue, size - insert, insert);
        //insert 为老数组大小
        insert = size;
        //设置remove=0
        remove = 0;
        //设置队列大小入容量大小都为新数组容量
        queue = newQueue;
        size = newSize;
    }

    //size方法  使用插入-读到的  还有没读的
    public synchronized int size() {
        int result = insert - remove;
        //如果读超了
        if (result < 0) {
            result += size;
        }
        return result;
    }

    //清空所有读取指针
    public synchronized void clear() {
        queue = new Object[size];
        insert = 0;
        remove = 0;
    }
}
