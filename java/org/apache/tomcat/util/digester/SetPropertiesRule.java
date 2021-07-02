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


import org.apache.tomcat.util.IntrospectionUtils;
import org.xml.sax.Attributes;


/**
 * <p>Rule implementation that sets properties on the object at the top of the
 * stack, based on attributes with corresponding names.</p>
 *
 * 此类是取出元素属性的一个规则类
 */

public class SetPropertiesRule extends Rule {

    /**
     * Process the beginning of this element.
     *
     * @param namespace the namespace URI of the matching element, or an
     *   empty string if the parser is not namespace aware or the element has
     *   no namespace
     * @param theName the local name if the parser is namespace aware, or just
     *   the element name otherwise
     * @param attributes The attribute list for this element
     */
    @Override
    public void begin(String namespace, String theName, Attributes attributes)
            throws Exception {

        // Populate the corresponding properties of the top object
        //通常情况下SetPropertiesRule这个Rule会放入标签解析规则的第二个，Object 会取出Rule第一个生成的对象
        Object top = digester.peek();
        if (digester.log.isDebugEnabled()) {
            if (top != null) {
                digester.log.debug("[SetPropertiesRule]{" + digester.match +
                                   "} Set " + top.getClass().getName() +
                                   " properties");
            } else {
                digester.log.debug("[SetPropertiesRule]{" + digester.match +
                                   "} Set NULL properties");
            }
        }
        //遍历标签上的属性
        for (int i = 0; i < attributes.getLength(); i++) {
            //取出标签上的属性 如port
            String name = attributes.getLocalName(i);
            //查看这个属性确定不是空的
            if ("".equals(name)) {
                name = attributes.getQName(i);
            }
            //取出这个属性的值 如8005
            String value = attributes.getValue(i);

            if (digester.log.isDebugEnabled()) {
                digester.log.debug("[SetPropertiesRule]{" + digester.match +
                        "} Setting property '" + name + "' to '" +
                        value + "'");
            }
            //!digester.isFakeAttribute(top, name) 判断这个属性是否是实例对象中不存在的属性，如果不存在就不需要继续往后执行了
            //IntrospectionUtils.setProperty(top, name, value)给top对象赋值 属性为name 值为value   并判断 给对象赋值是否成功
            //digester.getRulesValidation()
            if (!digester.isFakeAttribute(top, name)
                    && !IntrospectionUtils.setProperty(top, name, value)
                    && digester.getRulesValidation()) {
                digester.log.warn("[SetPropertiesRule]{" + digester.match +
                        "} Setting property '" + name + "' to '" +
                        value + "' did not find a matching property.");
            }
        }

    }


    /**
     * Render a printable version of this Rule.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SetPropertiesRule[");
        sb.append("]");
        return sb.toString();
    }
}
