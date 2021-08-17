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


package org.apache.tomcat.util.digester;


import org.xml.sax.Attributes;


/**
 * Rule implementation that creates a new object and pushes it
 * onto the object stack.  When the element is complete, the
 * object will be popped
 * 这个类只处理Listener 标签
 */

public class ObjectCreateRule extends Rule {


    // ----------------------------------------------------------- Constructors


    /**
     * Construct an object create rule with the specified class name.
     *
     * @param className Java class name of the object to be created
     */
    public ObjectCreateRule(String className) {

        this(className, (String) null);

    }


    /**
     * Construct an object create rule with the specified class name and an
     * optional attribute name containing an override.
     *
     * @param className     Java class name of the object to be created
     * @param attributeName Attribute name which, if present, contains an
     *                      override of the class name to create
     */
    public ObjectCreateRule(String className,
                            String attributeName) {

        this.className = className;
        this.attributeName = attributeName;

    }


    // ----------------------------------------------------- Instance Variables

    /**
     * The attribute containing an override class name if it is present.
     */
    protected String attributeName = null;


    /**
     * The Java class name of the object to be created.
     */
    protected String className = null;


    // --------------------------------------------------------- Public Methods


    /**
     * Process the beginning of this element.
     *
     * @param namespace  the namespace URI of the matching element, or an
     *                   empty string if the parser is not namespace aware or the element has
     *                   no namespace
     * @param name       the local name if the parser is namespace aware, or just
     *                   the element name otherwise
     * @param attributes The attribute list for this element
     */
    @Override
    public void begin(String namespace, String name, Attributes attributes)
            throws Exception {

        // Identify the name of the class to instantiate
        //尝试当前实例是否可以解析这个xml 中的属性，先给realClassName 赋值一个默认值
        // 然后通过className尝试获取当前元素的属性 详情请看下下行
        String realClassName = className;
        if (attributeName != null) {
            //就是这行 取到了 就说明当前元素存在 当前对象可解析的属性  就说嘛不可以呗   （当解析不到对应属性时 ，就使用默认值）
            String value = attributes.getValue(attributeName);
            if (value != null) {
                realClassName = value;
            }
        }
        if (digester.log.isDebugEnabled()) {
            digester.log.debug("[ObjectCreateRule]{" + digester.match +
                    "}New " + realClassName);
        }
        //这个值必须得有，如果没有就得给他抛出异常
        if (realClassName == null) {
            throw new NullPointerException("No class name specified for " +
                    namespace + " " + name);
        }

        // Instantiate the new object and push it on the context stack
        //最终走到这一步然后先装载这个类，后实例化这个类
        Class<?> clazz = digester.getClassLoader().loadClass(realClassName);
        Object instance = clazz.getConstructor().newInstance();
        //将实例好的对象都给他放到digester中的stack属性中
        digester.push(instance);
    }


    /**
     * Process the end of this element.
     *
     * @param namespace the namespace URI of the matching element, or an
     *                  empty string if the parser is not namespace aware or the element has
     *                  no namespace
     * @param name      the local name if the parser is namespace aware, or just
     *                  the element name otherwise
     */
    @Override
    public void end(String namespace, String name) throws Exception {

        Object top = digester.pop();
        if (digester.log.isDebugEnabled()) {
            digester.log.debug("[ObjectCreateRule]{" + digester.match +
                    "} Pop " + top.getClass().getName());
        }

    }


    /**
     * Render a printable version of this Rule.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ObjectCreateRule[");
        sb.append("className=");
        sb.append(className);
        sb.append(", attributeName=");
        sb.append(attributeName);
        sb.append("]");
        return sb.toString();
    }


}
