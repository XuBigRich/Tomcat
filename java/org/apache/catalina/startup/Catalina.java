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


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.security.SecurityConfig;
import org.apache.juli.ClassLoaderLogManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.Rule;
import org.apache.tomcat.util.digester.RuleSet;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.res.StringManager;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;


/**
 * Startup/Shutdown shell program for Catalina.  The following command line
 * options are recognized:
 * <ul>
 * <li><b>-config {pathname}</b> - Set the pathname of the configuration file
 *     to be processed.  If a relative path is specified, it will be
 *     interpreted as relative to the directory pathname specified by the
 *     "catalina.base" system property.   [conf/server.xml]</li>
 * <li><b>-help</b>      - Display usage information.</li>
 * <li><b>-nonaming</b>  - Disable naming support.</li>
 * <li><b>configtest</b> - Try to test the config</li>
 * <li><b>start</b>      - Start an instance of Catalina.</li>
 * <li><b>stop</b>       - Stop the currently running instance of Catalina.</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class Catalina {


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
            StringManager.getManager(Constants.Package);


    // ----------------------------------------------------- Instance Variables

    /**
     * Use await.
     */
    protected boolean await = false;

    /**
     * Pathname to the server configuration file.
     */
    protected String configFile = "conf/server.xml";

    // XXX Should be moved to embedded
    /**
     * The shared extensions class loader for this server.
     */
    protected ClassLoader parentClassLoader =
            Catalina.class.getClassLoader();


    /**
     * The server component we are starting or stopping.
     */
    protected Server server = null;


    /**
     * Use shutdown hook flag.
     */
    protected boolean useShutdownHook = true;


    /**
     * Shutdown hook.
     */
    protected Thread shutdownHook = null;


    /**
     * Is naming enabled ?
     */
    protected boolean useNaming = true;


    /**
     * Prevent duplicate loads.
     */
    protected boolean loaded = false;


    // ----------------------------------------------------------- Constructors

    public Catalina() {
        setSecurityProtection();
        ExceptionUtils.preload();
    }


    // ------------------------------------------------------------- Properties

    public void setConfigFile(String file) {
        configFile = file;
    }


    public String getConfigFile() {
        return configFile;
    }


    public void setUseShutdownHook(boolean useShutdownHook) {
        this.useShutdownHook = useShutdownHook;
    }


    public boolean getUseShutdownHook() {
        return useShutdownHook;
    }


    /**
     * Set the shared extensions class loader.
     *
     * @param parentClassLoader The shared extensions class loader.
     */
    public void setParentClassLoader(ClassLoader parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
    }

    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        return ClassLoader.getSystemClassLoader();
    }

    //当配置文件执行完毕时，会将生成的Server 通过这个方法 赋值给Catalina
    public void setServer(Server server) {
        this.server = server;
    }


    public Server getServer() {
        return server;
    }


    /**
     * @return <code>true</code> if naming is enabled.
     */
    public boolean isUseNaming() {
        return this.useNaming;
    }


    /**
     * Enables or disables naming support.
     *
     * @param useNaming The new use naming value
     */
    public void setUseNaming(boolean useNaming) {
        this.useNaming = useNaming;
    }

    public void setAwait(boolean b) {
        await = b;
    }

    public boolean isAwait() {
        return await;
    }

    // ------------------------------------------------------ Protected Methods


    /**
     * Process the specified command line arguments.
     *
     * @param args Command line arguments to process
     * @return <code>true</code> if we should continue processing
     */
    protected boolean arguments(String args[]) {

        boolean isConfig = false;

        if (args.length < 1) {
            usage();
            return false;
        }

        for (int i = 0; i < args.length; i++) {
            if (isConfig) {
                configFile = args[i];
                isConfig = false;
            } else if (args[i].equals("-config")) {
                isConfig = true;
            } else if (args[i].equals("-nonaming")) {
                setUseNaming(false);
            } else if (args[i].equals("-help")) {
                usage();
                return false;
            } else if (args[i].equals("start")) {
                // NOOP
            } else if (args[i].equals("configtest")) {
                // NOOP
            } else if (args[i].equals("stop")) {
                // NOOP
            } else {
                usage();
                return false;
            }
        }

        return true;
    }


    /**
     * Return a File object representing our configuration file.
     *
     * @return the main configuration file
     */
    protected File configFile() {

        File file = new File(configFile);
        if (!file.isAbsolute()) {
            file = new File(Bootstrap.getCatalinaBase(), configFile);
        }
        return file;

    }


    /**
     * Create and configure the Digester we will be using for startup.
     *
     * @return the main digester to parse server.xml
     * 此方法就是给 Digester实例（xml解析器）添加各种 解析规则
     * <p>
     * 配置文件中 所有Listener 结尾的数据都将被添加到 LifecycleMBeanBase 的 lifecycleListeners属性
     */
    protected Digester createStartDigester() {
        //获取系统时间
        long t1 = System.currentTimeMillis();
        // Initialize the digester
        // Digester这个类继承自DefaultHandler2 而DefaultHandler2这个类是jdk提供的处理xml解析的一个类
        Digester digester = new Digester();
        digester.setValidating(false);
        digester.setRulesValidation(true);
        Map<Class<?>, List<String>> fakeAttributes = new HashMap<>();
        List<String> objectAttrs = new ArrayList<>();
        objectAttrs.add("className");
        fakeAttributes.put(Object.class, objectAttrs);
        // Ignore attribute added by Eclipse for its internal tracking
        List<String> contextAttrs = new ArrayList<>();
        contextAttrs.add("source");
        fakeAttributes.put(StandardContext.class, contextAttrs);
        //这个意思就是说StandardContext.class 与 Object.class都称作FakeAttributes
        digester.setFakeAttributes(fakeAttributes);
        digester.setUseContextClassLoader(true);

        // Configure the actions we will be using
        //patten 为规则名称，className 与attributeName 用于建立ObjectCreateRule类型的规则实体类 该类继承自 Rule
        //这表明所有的className都将由StandardServer来解析
        //解析时遇到Server标签后StandardServer将被实例化后 放入Digester的 stack属性，Server执行完毕后 才会移除
        //他的执行时机是第一个执行
        //此时堆栈（stack属性）最顶层是 Catalina 第二层就是StandardServer        ObjectCreateRule extends Rule
        //className是默认初始化的 类
        //attributeName是要用来判断是否存在这个属性的
        digester.addObjectCreate("Server",
                "org.apache.catalina.core.StandardServer",
                "className");
        //给digester添加一个参数sever 表明 当前digester 已经支持 Server类型的 规则解析器
        //他的执行时机是当地一个规则执行完毕后 产生实例后 ，才开始执行                SetPropertiesRule extends Rule
        digester.addSetProperties("Server");
        //patten 为规则名称，className 与attributename 用于建立SetNext类型的规则实体类 Rule
        //他的执行时机与前两个不同 它是重写了end方法也就是在元素结束时才会执行         SetNextRule extends Rule
        //addSetNext 方法 的索引为Server  ，在结束时执行setServer方法，注意是待Server标签结束时执行setServer方法
        digester.addSetNext("Server",
                "setServer",
                "org.apache.catalina.Server");
        //原理同上
        digester.addObjectCreate("Server/GlobalNamingResources",
                "org.apache.catalina.deploy.NamingResourcesImpl");
        digester.addSetProperties("Server/GlobalNamingResources");
        digester.addSetNext("Server/GlobalNamingResources",
                "setGlobalNamingResources",
                "org.apache.catalina.deploy.NamingResourcesImpl");
        //原理同上
        digester.addObjectCreate("Server/Listener",
                null, // MUST be specified in the element
                "className");
        digester.addSetProperties("Server/Listener");
        digester.addSetNext("Server/Listener",
                "addLifecycleListener",
                "org.apache.catalina.LifecycleListener");
        //原理同上
        digester.addObjectCreate("Server/Service",
                "org.apache.catalina.core.StandardService",
                "className");
        digester.addSetProperties("Server/Service");
        digester.addSetNext("Server/Service",
                "addService",
                "org.apache.catalina.Service");
        //原理同上
        digester.addObjectCreate("Server/Service/Listener",
                null, // MUST be specified in the element
                "className");
        digester.addSetProperties("Server/Service/Listener");
        digester.addSetNext("Server/Service/Listener",
                "addLifecycleListener",
                "org.apache.catalina.LifecycleListener");

        //原理同上
        digester.addObjectCreate("Server/Service/Executor",
                "org.apache.catalina.core.StandardThreadExecutor",
                "className");
        digester.addSetProperties("Server/Service/Executor");

        digester.addSetNext("Server/Service/Executor",
                "addExecutor",
                "org.apache.catalina.Executor");

        //创建一个Connector 对象
        digester.addRule("Server/Service/Connector",
                new ConnectorCreateRule());
        //声明这个解析器会解析配置文件中的executor 与sslImplementationName属性
        digester.addRule("Server/Service/Connector",
                new SetAllPropertiesRule(new String[]{"executor", "sslImplementationName"}));
        //给Service类添加Connector 属性
        digester.addSetNext("Server/Service/Connector",
                "addConnector",
                "org.apache.catalina.connector.Connector");
        //原理同上
        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig",
                //如果server.xml配置了 SSLHostConfig标签，那么会生成一个SSLHostConfig类
                "org.apache.tomcat.util.net.SSLHostConfig");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig",
                "addSslHostConfig",
                "org.apache.tomcat.util.net.SSLHostConfig");
        //原理同上
        digester.addRule("Server/Service/Connector/SSLHostConfig/Certificate",
                new CertificateCreateRule());
        digester.addRule("Server/Service/Connector/SSLHostConfig/Certificate",
                new SetAllPropertiesRule(new String[]{"type"}));
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/Certificate",
                "addCertificate",
                "org.apache.tomcat.util.net.SSLHostConfigCertificate");
        //原理同上
        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig/OpenSSLConf",
                "org.apache.tomcat.util.net.openssl.OpenSSLConf");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/OpenSSLConf");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/OpenSSLConf",
                "setOpenSslConf",
                "org.apache.tomcat.util.net.openssl.OpenSSLConf");
        //原理同上
        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd",
                "org.apache.tomcat.util.net.openssl.OpenSSLConfCmd");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd",
                "addCmd",
                "org.apache.tomcat.util.net.openssl.OpenSSLConfCmd");
        //原理同上
        digester.addObjectCreate("Server/Service/Connector/Listener",
                null, // MUST be specified in the element
                "className");
        digester.addSetProperties("Server/Service/Connector/Listener");
        digester.addSetNext("Server/Service/Connector/Listener",
                "addLifecycleListener",
                "org.apache.catalina.LifecycleListener");
        //原理同上
        digester.addObjectCreate("Server/Service/Connector/UpgradeProtocol",
                null, // MUST be specified in the element
                "className");
        digester.addSetProperties("Server/Service/Connector/UpgradeProtocol");
        digester.addSetNext("Server/Service/Connector/UpgradeProtocol",
                "addUpgradeProtocol",
                "org.apache.coyote.UpgradeProtocol");

        // Add RuleSets for nested elements
        //添加各规则集，这个地方的xml解析与上方有了些许不同，这里时将声明好的解析规则放入，然后提供一个回调方法addRuleInstances，
        //之所以这样设计是因为在他们的下层还有许多标签需要解析，他们的子标签解析方案需要放到对应的Rule容器中
        //如解析GlobalNamingResources标签下的资源 ，都提前放入到了NamingRuleSet里面，他提供一个addRuleInstances方法，等待RuleSet的回调
        digester.addRuleSet(new NamingRuleSet("Server/GlobalNamingResources/"));
        //EngineRuleSet 类里面的xml解析规则很是壮观
        digester.addRuleSet(new EngineRuleSet("Server/Service/"));
        digester.addRuleSet(new HostRuleSet("Server/Service/Engine/"));
        digester.addRuleSet(new ContextRuleSet("Server/Service/Engine/Host/"));
        //addClusterRuleSet方法会创建一个ClusterRuleSet实例  并将实例赋值给digester的
        addClusterRuleSet(digester, "Server/Service/Engine/Host/Cluster/");
        digester.addRuleSet(new NamingRuleSet("Server/Service/Engine/Host/Context/"));

        // When the 'engine' is found, set the parentClassLoader.
        //digester
        digester.addRule("Server/Service/Engine",
                new SetParentClassLoaderRule(parentClassLoader));
        //addClusterRuleSet方法会创建一个ClusterRuleSet实例  并将实例赋值给digester的
        addClusterRuleSet(digester, "Server/Service/Engine/Cluster/");
        //记录启动时间
        long t2 = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("Digester for server.xml created " + (t2 - t1));
        }
        return digester;

    }

    /**
     * 将解析xml 的解析器  prefix当作实例化参数传入ClusterRuleSet构造器
     * Cluster support is optional. The JARs may have been removed.
     */
    private void addClusterRuleSet(Digester digester, String prefix) {
        Class<?> clazz = null;
        Constructor<?> constructor = null;
        try {
            //获取org.apache.catalina.ha.ClusterRuleSet类类型  里面包含很多已经写好的xml解析规则。
            // 调用ClusterRuleSet里面的addRuleInstances方法  可直接为xml解析器（digester）提供解析规则
            clazz = Class.forName("org.apache.catalina.ha.ClusterRuleSet");
            //获取ClusterRuleSet类含有String参数的 构造器
            constructor = clazz.getConstructor(String.class);
            //生成RuleSet
            RuleSet ruleSet = (RuleSet) constructor.newInstance(prefix);
            //给xml解析器设置
            digester.addRuleSet(ruleSet);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("catalina.noCluster",
                        e.getClass().getName() + ": " + e.getMessage()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("catalina.noCluster",
                        e.getClass().getName() + ": " + e.getMessage()));
            }
        }
    }

    /**
     * Create and configure the Digester we will be using for shutdown.
     *
     * @return the digester to process the stop operation
     */
    protected Digester createStopDigester() {

        // Initialize the digester
        Digester digester = new Digester();
        digester.setUseContextClassLoader(true);

        // Configure the rules we need for shutting down
        digester.addObjectCreate("Server",
                "org.apache.catalina.core.StandardServer",
                "className");
        digester.addSetProperties("Server");
        digester.addSetNext("Server",
                "setServer",
                "org.apache.catalina.Server");

        return digester;

    }


    public void stopServer() {
        stopServer(null);
    }

    public void stopServer(String[] arguments) {

        if (arguments != null) {
            arguments(arguments);
        }

        Server s = getServer();
        if (s == null) {
            // Create and execute our Digester
            Digester digester = createStopDigester();
            File file = configFile();
            try (FileInputStream fis = new FileInputStream(file)) {
                InputSource is =
                        new InputSource(file.toURI().toURL().toString());
                is.setByteStream(fis);
                digester.push(this);
                digester.parse(is);
            } catch (Exception e) {
                log.error("Catalina.stop: ", e);
                System.exit(1);
            }
        } else {
            // Server object already present. Must be running as a service
            try {
                s.stop();
                s.destroy();
            } catch (LifecycleException e) {
                log.error("Catalina.stop: ", e);
            }
            return;
        }

        // Stop the existing server
        s = getServer();
        if (s.getPort() > 0) {
            try (Socket socket = new Socket(s.getAddress(), s.getPort());
                 OutputStream stream = socket.getOutputStream()) {
                String shutdown = s.getShutdown();
                for (int i = 0; i < shutdown.length(); i++) {
                    stream.write(shutdown.charAt(i));
                }
                stream.flush();
            } catch (ConnectException ce) {
                log.error(sm.getString("catalina.stopServer.connectException",
                        s.getAddress(),
                        String.valueOf(s.getPort())));
                log.error("Catalina.stop: ", ce);
                System.exit(1);
            } catch (IOException e) {
                log.error("Catalina.stop: ", e);
                System.exit(1);
            }
        } else {
            log.error(sm.getString("catalina.stopServer"));
            System.exit(1);
        }
    }


    /**
     * Start a new server instance.   创建一个server 实例
     * <p>
     * 这个地方最最最重要的，他的存在的意义是 通过读取配置文件server.xml，然后将同配置文件中的所有类  和属性映射到Server类中，当成一个属性
     * org.apache.catalina.core.StandardServer
     */
    public void load() {
        //判断一下load 方法是否已经被调用过了  如果被调用过直接返回
        if (loaded) {
            return;
        }
        //打一下标示标示 load方法已经被调用了  不让其他方法再调用load方法了
        loaded = true;
        //获取当前系统时间 纳秒单位
        long t1 = System.nanoTime();
        //确保设置 Java虚拟机中的系统变量  java.io.tmpdir 存在
        initDirs();

        // Before digester - it may be needed
        //为Java虚拟机设置系统变量 （java.naming.factory.initial，java.naming.factory.url.pkgs）
        initNaming();

        // Create and execute our Digester
        //初始化xml解析器，配备各种解析规则  （这个地方是重点，他囊括了，当碰到指定dom时，开始应该怎么做，过程中应该怎么做，结束应该怎么做）
        Digester digester = createStartDigester();
        //令人迷惑的InputSource
        InputSource inputSource = null;
        //输入流
        InputStream inputStream = null;
        File file = null;
        try {
            try {
                //将conf/server.xml 实例化为一个File对象
                file = configFile();
                //使用文件输入流读取这个文件
                inputStream = new FileInputStream(file);
                //使用inputSource读取这个文件
                inputSource = new InputSource(file.toURI().toURL().toString());
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("catalina.configFail", file), e);
                }
            }
            //如果文件输入流为null
            if (inputStream == null) {
                try {
                    //尝试使用类加载器下的资源目录 再次加载一下conf/server.xml 文件流
                    inputStream = getClass().getClassLoader()
                            .getResourceAsStream(getConfigFile());
                    inputSource = new InputSource
                            (getClass().getClassLoader()
                                    .getResource(getConfigFile()).toString());
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("catalina.configFail",
                                getConfigFile()), e);
                    }
                }
            }

            // This should be included in catalina.jar
            // Alternative: don't bother with xml, just create it manually.
            if (inputStream == null) {
                try {
                    //如果前面的inputStream依然没有获取到conf/server.xml配置文件 那么就获取/读取 当前类加载器的server-embed.xml配置文件
                    inputStream = getClass().getClassLoader()
                            .getResourceAsStream("server-embed.xml");
                    inputSource = new InputSource
                            (getClass().getClassLoader()
                                    .getResource("server-embed.xml").toString());
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("catalina.configFail",
                                "server-embed.xml"), e);
                    }
                }
            }

            //如果依然为null 那就没辙了 该报错报错，该死亡死亡hhhhh
            if (inputStream == null || inputSource == null) {
                if (file == null) {
                    log.warn(sm.getString("catalina.configFail",
                            getConfigFile() + "] or [server-embed.xml]"));
                } else {
                    log.warn(sm.getString("catalina.configFail",
                            file.getAbsolutePath()));
                    if (file.exists() && !file.canRead()) {
                        log.warn("Permissions incorrect, read permission is not allowed on the file.");
                    }
                }
                return;
            }

            try {
                //给inputSource设置输入流
                inputSource.setByteStream(inputStream);
                //将当前实例放入文件解析器
                digester.push(this);
                //重点 重点 重点              使用预先设置好的文件解析器 开始解析 配置文件 装载server 服务等 生成了xml解析器
                digester.parse(inputSource);
            } catch (SAXParseException spe) {
                log.warn("Catalina.start using " + getConfigFile() + ": " +
                        spe.getMessage());
                return;
            } catch (Exception e) {
                log.warn("Catalina.start using " + getConfigFile() + ": ", e);
                return;
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        //给当前server设置Catalina为当前对象
        getServer().setCatalina(this);
        //为当前server设置Catalina的家目录
        getServer().setCatalinaHome(Bootstrap.getCatalinaHomeFile());
        //为当前server设置Catalina的Base目录 通常情况下与家目录相同
        getServer().setCatalinaBase(Bootstrap.getCatalinaBaseFile());

        // Stream redirection
        //设置标准输出 标准错误输出
        initStreams();

        // Start the new server
        try {
            //进行服务初始化，会将生成好的server对象进行初始化，更改Lifecycle.state状态
            //这个地方会对 server.xml的所有类进行初始化
            getServer().init();
        } catch (LifecycleException e) {
            if (Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE")) {
                throw new java.lang.Error(e);
            } else {
                log.error("Catalina.start", e);
            }
        }

        long t2 = System.nanoTime();
        if (log.isInfoEnabled()) {
            log.info("Initialization processed in " + ((t2 - t1) / 1000000) + " ms");
        }
    }


    /**
     * Load using arguments
     * 此方法是由Bootstrap 类 入口进入的，当进入这个方法时，代表着Bootstrap类的工作已经告一段落了
     *
     * @param args 用户启动程序时传入的参数
     */
    public void load(String args[]) {

        try {
            if (arguments(args)) {
                load();
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }


    /**
     * Start a new server instance.
     */
    public void start() {
        //判断server属性是否为null，第一次启动时 server为null
        if (getServer() == null) {
            //加载解析器，并解析server.xml配置文件
            load();
        }

        if (getServer() == null) {
            log.fatal("Cannot start server. Server instance is not configured.");
            return;
        }

        long t1 = System.nanoTime();

        // Start the new server
        //解析完配置文件后会给本对象的 server属性赋值，执行start函数
        try {
            getServer().start();
        } catch (LifecycleException e) {
            log.fatal(sm.getString("catalina.serverStartFail"), e);
            try {
                getServer().destroy();
            } catch (LifecycleException e1) {
                log.debug("destroy() failed for failed Server ", e1);
            }
            return;
        }

        long t2 = System.nanoTime();
        if (log.isInfoEnabled()) {
            log.info("Server startup in " + ((t2 - t1) / 1000000) + " ms");
        }

        // Register shutdown hook
        if (useShutdownHook) {
            if (shutdownHook == null) {
                shutdownHook = new CatalinaShutdownHook();
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // If JULI is being used, disable JULI's shutdown hook since
            // shutdown hooks run in parallel and log messages may be lost
            // if JULI's hook completes before the CatalinaShutdownHook()
            LogManager logManager = LogManager.getLogManager();
            if (logManager instanceof ClassLoaderLogManager) {
                ((ClassLoaderLogManager) logManager).setUseShutdownHook(
                        false);
            }
        }

        if (await) {
            await();
            stop();
        }
    }


    /**
     * Stop an existing server instance.
     */
    public void stop() {

        try {
            // Remove the ShutdownHook first so that server.stop()
            // doesn't get invoked twice
            if (useShutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);

                // If JULI is being used, re-enable JULI's shutdown to ensure
                // log messages are not lost
                LogManager logManager = LogManager.getLogManager();
                if (logManager instanceof ClassLoaderLogManager) {
                    ((ClassLoaderLogManager) logManager).setUseShutdownHook(
                            true);
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This will fail on JDK 1.2. Ignoring, as Tomcat can run
            // fine without the shutdown hook.
        }

        // Shut down the server
        try {
            Server s = getServer();
            LifecycleState state = s.getState();
            if (LifecycleState.STOPPING_PREP.compareTo(state) <= 0
                    && LifecycleState.DESTROYED.compareTo(state) >= 0) {
                // Nothing to do. stop() was already called
            } else {
                s.stop();
                s.destroy();
            }
        } catch (LifecycleException e) {
            log.error("Catalina.stop", e);
        }

    }


    /**
     * Await and shutdown.
     */
    public void await() {

        getServer().await();

    }


    /**
     * Print usage information for this application.
     */
    protected void usage() {

        System.out.println
                ("usage: java org.apache.catalina.startup.Catalina"
                        + " [ -config {pathname} ]"
                        + " [ -nonaming ] "
                        + " { -help | start | stop }");

    }

    //读取配置文件 java 的临时文件夹
    protected void initDirs() {
        //获取默认的临时文件路径
        String temp = System.getProperty("java.io.tmpdir");
        //判断这个临时路径是否存在
        if (temp == null || (!(new File(temp)).isDirectory())) {
            log.error(sm.getString("embedded.notmp", temp));
        }
    }


    protected void initStreams() {
        // Replace System.out and System.err with a custom PrintStream
        //设置输出
        System.setOut(new SystemLogHandler(System.out));
        //设置错误输出
        System.setErr(new SystemLogHandler(System.err));
    }


    protected void initNaming() {
        // Setting additional variables  第一次调用的时候 使用的是Catalina 初始化的默认值 即true
        if (!useNaming) {
            log.info("Catalina naming disabled");
            System.setProperty("catalina.useNaming", "false");
        } else {
            //设置jvm虚拟机中的catalina.useNaming为true
            System.setProperty("catalina.useNaming", "true");
            //声明一个变量value
            String value = "org.apache.naming";
            //从虚拟机参数中取出 java.naming.factory.url.pkgs 的变量（第一次启动 是null 因为默认的虚拟机参数中并不包括 这个变量key）
            String oldValue =
                    System.getProperty(javax.naming.Context.URL_PKG_PREFIXES);
            //因此 当第一次启动时 就不会进入这个if了 所以当第二次进入这个方法时 就会进入if
            if (oldValue != null) {
                //老值与新值之间使用：隔断
                value = value + ":" + oldValue;
            }
            //进行java.naming.factory.url.pkgs  参数的赋值，这个参数的value 的最初始值为org.apache.naming
            System.setProperty(javax.naming.Context.URL_PKG_PREFIXES, value);
            if (log.isDebugEnabled()) {
                log.debug("Setting naming prefix=" + value);
            }
            //获取设置给Java虚拟机中java.naming.factory.initial的环境变量
            value = System.getProperty
                    (javax.naming.Context.INITIAL_CONTEXT_FACTORY);
            //如果环境变量为null 就给他设置上 环境 变量为org.apache.naming.java.javaURLContextFactory
            //如果不为null 说明已经设置过了 直接日志打印出来说设置过了就可以
            if (value == null) {
                System.setProperty
                        (javax.naming.Context.INITIAL_CONTEXT_FACTORY,
                                "org.apache.naming.java.javaURLContextFactory");
            } else {
                log.debug("INITIAL_CONTEXT_FACTORY already set " + value);
            }
        }
    }


    /**
     * Set the security package access/protection.
     */
    protected void setSecurityProtection() {
        SecurityConfig securityConfig = SecurityConfig.newInstance();
        securityConfig.setPackageDefinition();
        securityConfig.setPackageAccess();
    }


    // --------------------------------------- CatalinaShutdownHook Inner Class

    // XXX Should be moved to embedded !

    /**
     * Shutdown hook which will perform a clean shutdown of Catalina if needed.
     */
    protected class CatalinaShutdownHook extends Thread {

        @Override
        public void run() {
            try {
                if (getServer() != null) {
                    Catalina.this.stop();
                }
            } catch (Throwable ex) {
                ExceptionUtils.handleThrowable(ex);
                log.error(sm.getString("catalina.shutdownHookFail"), ex);
            } finally {
                // If JULI is used, shut JULI down *after* the server shuts down
                // so log messages aren't lost
                LogManager logManager = LogManager.getLogManager();
                if (logManager instanceof ClassLoaderLogManager) {
                    ((ClassLoaderLogManager) logManager).shutdown();
                }
            }
        }
    }


    private static final Log log = LogFactory.getLog(Catalina.class);

}


// ------------------------------------------------------------ Private Classes


/**
 * Rule that sets the parent class loader for the top object on the stack,
 * which must be a <code>Container</code>.
 * 该类继承自Rule类
 * 创建该类实例需要接收一个类加载器参数
 */

final class SetParentClassLoaderRule extends Rule {

    public SetParentClassLoaderRule(ClassLoader parentClassLoader) {

        this.parentClassLoader = parentClassLoader;

    }

    ClassLoader parentClassLoader = null;

    @Override
    public void begin(String namespace, String name, Attributes attributes)
            throws Exception {

        if (digester.getLogger().isDebugEnabled()) {
            digester.getLogger().debug("Setting parent class loader");
        }

        Container top = (Container) digester.peek();
        top.setParentClassLoader(parentClassLoader);

    }


}
