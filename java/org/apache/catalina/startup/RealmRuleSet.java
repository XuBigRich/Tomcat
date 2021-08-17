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


package org.apache.catalina.startup;


import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.RuleSetBase;


/**
 * <p><strong>RuleSet</strong> for processing the contents of a Realm definition
 * element.  This <code>RuleSet</code> supports Realms such as the
 * <code>CombinedRealm</code> that used nested Realms.</p>
 */
@SuppressWarnings("deprecation")
public class RealmRuleSet extends RuleSetBase {

    //从系统变量中取出org.apache.catalina.startup.RealmRuleSet.MAX_NESTED_REALM_LEVELS 如果没有设置那么默认给他一个3
    private static final int MAX_NESTED_REALM_LEVELS = Integer.getInteger(
            "org.apache.catalina.startup.RealmRuleSet.MAX_NESTED_REALM_LEVELS",
            3).intValue();

    // ----------------------------------------------------- Instance Variables


    /**
     * The matching pattern prefix to use for recognizing our elements.
     */
    protected final String prefix;


    // ------------------------------------------------------------ Constructor


    /**
     * Construct an instance of this <code>RuleSet</code> with the default
     * matching pattern prefix.
     */
    public RealmRuleSet() {
        this("");
    }


    /**
     * Construct an instance of this <code>RuleSet</code> with the specified
     * matching pattern prefix.
     *
     * @param prefix Prefix for matching pattern rules (including the
     *               trailing slash character)
     */
    public RealmRuleSet(String prefix) {
        this.prefix = prefix;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * <p>Add the set of Rule instances defined in this RuleSet to the
     * specified <code>Digester</code> instance, associating them with
     * our namespace URI (if any).  This method should only be called
     * by a Digester instance.</p>
     *
     * @param digester Digester instance to which the new Rule instances
     *                 should be added.
     *                 这个地方的回调方法有一点点不同，是因为 Realm 标签里面可以继续嵌套一个Realm标签，他们在Server中的体现形式就是 一个List
     */
    @Override
    public void addRuleInstances(Digester digester) {
        //Engine下面一定下挂Realm,此处就是进行拼接索引
        StringBuilder pattern = new StringBuilder(prefix);
        for (int i = 0; i < MAX_NESTED_REALM_LEVELS; i++) {
            if (i > 0) {
                pattern.append('/');
            }
            pattern.append("Realm");
            //如果时第一次遍历 就设置Realm,如果不是第一次 就可以执行添加了
            addRuleInstances(digester, pattern.toString(), i == 0 ? "setRealm" : "addRealm");
        }
    }

    private void addRuleInstances(Digester digester, String pattern, String methodName) {
        digester.addObjectCreate(pattern, null /* MUST be specified in the element */,
                "className");
        digester.addSetProperties(pattern);
        //传入匹配索引如：Server/Service/Engine/Host/Realm/Realm（因为Realm比较特殊，可以嵌套再嵌套）,要执行的方法，传入的默认参数类型（结束时使用）
        digester.addSetNext(pattern, methodName, "org.apache.catalina.Realm");
        digester.addRuleSet(new CredentialHandlerRuleSet(pattern + "/"));
    }
}
