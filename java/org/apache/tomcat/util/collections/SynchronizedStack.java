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
 * create a pool of re-usable objects with no requirement to shrink the pool.
 * The aim is to provide the bare minimum of required functionality as quickly
 * as possible with minimum garbage.
 *
 * @param <T> The type of object managed by this stack
 */
public class SynchronizedStack<T> {

    public static final int DEFAULT_SIZE = 128;
    private static final int DEFAULT_LIMIT = -1;

    private int size;
    private final int limit;

    /*
     * Points to the next available object in the stack
     */
    private int index = -1;

    private Object[] stack;

    //默认的构造函数默认初始大小为128 默认limit -1（不启用栈大小限制）
    public SynchronizedStack() {
        this(DEFAULT_SIZE, DEFAULT_LIMIT);
    }

    //如果limit>-1 表明自定义的。
    // 且size不可以大于limit
    // 大于了limit  那么就以limit为主
    public SynchronizedStack(int size, int limit) {
        if (limit > -1 && size > limit) {
            this.size = limit;
        } else {
            this.size = size;
        }
        //设置限制大小
        this.limit = limit;
        //设置声明一个底层数组
        stack = new Object[size];
    }

    //压栈操作 先进后出
    public synchronized boolean push(T obj) {
        //栈顶读取指针+1
        index++;
        //栈顶达到顶峰
        if (index == size) {
//            如果limit==-1 ，栈大小限制未被启用，或者说 size当前的容量小与限制的大小（提供了扩容的空间）
            if (limit == -1 || size < limit) {
                //进行扩容
                expand();
            } else {
                //否哦则index栈顶指针归位，返回false告诉用户压栈失败
                index--;
                return false;
            }
        }
        //如果一切顺利 压入栈中
        stack[index] = obj;
        //返回true
        return true;
    }

    @SuppressWarnings("unchecked")
    //出栈操作
    public synchronized T pop() {
        //首先确定栈顶读取指针未达到-1
        if (index == -1) {
            return null;
        }
        //从栈顶取出数据 赋值给result
        T result = (T) stack[index];
        //将栈顶读取指针-1 然后将原来的栈顶置为null 协助GC
        stack[index--] = null;
        return result;
    }

    //清空栈
    public synchronized void clear() {
        //如果栈顶大于-1，栈里面还有数据
        if (index > -1) {
            //将栈中的数据都置为null
            for (int i = 0; i < index + 1; i++) {
                stack[i] = null;
            }
        }
        //栈顶读取指针置为-1
        index = -1;
    }
    //显然这是一个扩容方法
    private void expand() {
        //size用于描述栈容量 ，他扩容时栈容量扩容两倍
        int newSize = size * 2;
        //限制如果不是-1，说明这个栈 有特别要求，需要限制他的栈大小
        // 在设置栈大小时，入果新的容量大于栈底，那么以限制的大小为主
        //也就是说栈的大小不容许可以大过limit
        if (limit != -1 && newSize > limit) {
            //如果成立 ，那么设置新的容量大小为limit
            newSize = limit;
        }
        //建立一个新的数组
        Object[] newStack = new Object[newSize];
        //复制数组
        System.arraycopy(stack, 0, newStack, 0, size);
        // This is the only point where garbage is created by throwing away the
        // old array. Note it is only the array, not the contents, that becomes
        // garbage
        //更换底层数组为新声明的数组
        stack = newStack;
        //大小为新声明的大小
        size = newSize;
    }
}
