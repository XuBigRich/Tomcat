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
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import sun.misc.Launcher;

/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static final Object daemonLock = new Object();
    private static volatile Bootstrap daemon = null;

    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;
    //提取"中间文字内容的正则表达式
    private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");
    //整个静态方法块就干了一件事 就是将catalina.properties装载到系统变量中去
    static {
        // 获取tomcat 当前文件夹
        String userDir = System.getProperty("user.dir");
        System.out.println(userDir);
        // 获取catalina——home-prop
        String home = System.getProperty(Globals.CATALINA_HOME_PROP);
        File homeFile = null;
        //如果catalina——home-prop  不为null
        if (home != null) {
            //建立这个文件夹对象
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            //生成一个路径 当前项目所在文件夹/bootstrap.jar
            File bootstrapJar = new File(userDir, "bootstrap.jar");
            //判断在当前项目根目录下是否存在一个bootstrap的jar包
            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }
        //如果 系统变量中没有CATALINA_HOME_PROP 配置，且 在根项目下不存在 bootstrap.jar 包文件
        if (homeFile == null) {
            // 那么利用当前项目路径生成一个对象
            File f = new File(userDir);
            try {
                //获取当前路径 的标准地址
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }
        //从此以后 Bootstrap这个类的catalinaHomeFile属性 值 就是当前项目根路径了
        catalinaHomeFile = homeFile;
        //然后在系统变量中 把CATALINA_HOME_PROP 的值 设置为刚刚配置的项目根路径
        System.setProperty(
                Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath());
        //去系统变量中取CATALINA_BASE_PROP 这个变量 赋值给字符串base
        String base = System.getProperty(Globals.CATALINA_BASE_PROP);
        //如果base位null 也就是系统环境变量中没有CATALINA_BASE_PROP 这个变量
        if (base == null) {
            //那么 直接给他 把catalinaBaseFile设置成和catalinaHomeFile一样的值
            catalinaBaseFile = catalinaHomeFile;
        } else {
            //如果存在 那么根据系统变量取出来的值生成一个对象
            File baseFile = new File(base);
            try {
                //获取 当前系统标准的路径结构
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            //将值赋给 Bootstrap的catalinaBaseFile 属性
            catalinaBaseFile = baseFile;
        }
        //给系统变量设置CATALINA_BASE_PROP 属性，无则加上，有则覆盖
        System.setProperty(
                Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;

    ClassLoader commonLoader = null;
    ClassLoader catalinaLoader = null;
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods

    //初始类加载器
    private void initClassLoaders() {
        try {
            //BootStrap的配置文件 common.loade 都由 此方法初始化  由路径生成的URL数组 交由URLClassLoader 生成ClassLoader对象
            commonLoader = createClassLoader("common", null);
            if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader = this.getClass().getClassLoader();
            }
            //读取配置文件的server.load 由 commonLoader 加载
            catalinaLoader = createClassLoader("server", commonLoader);
            //读取配置文件的shareds.load 由 commonLoader 加载
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }

    //创建类加载器
    private ClassLoader createClassLoader(String name, ClassLoader parent)
            throws Exception {
        //每次加载类的时候要先确定一下 这个类的静态代码块的初始化问题

        //例如这个 类 你要先去查看一下 CatalinaProperties 的静态代码块，
        //这个类的静态代码块 作用就是从 catalina.config 配置文件中 读取配置 添加到Properties 静态对象中缓存
        //这个方法就是从 Properties静态对象中 获取 ${name}.loader的配置
        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals("")))
            return parent;
        //将读取出来的配置进行一些处理 请点进方法看处理内容
        value = replace(value);
        //声明一个Repository 列表 Repository这个类是Tomcat 自己定义的
        List<Repository> repositories = new ArrayList<>();
        //里面全是分割好了的配置文件，点方法进去看详情
        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                //Repository用于描述 资源的路径 和 资源类型
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // 如果这个配置文件 包含*.jar
            if (repository.endsWith("*.jar")) {
                //那就把*.jar去掉 类型填为GLOB
                repository = repository.substring
                        (0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } // 如果这个配置文件 包含.jar
            else if (repository.endsWith(".jar")) {
                //那就直接加入 list  但类型 填为JAR
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                //那就直接加入 list  类型 填为DIR
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
            //此时该方法走完 会将配置文件中的所有信息路径提取出来，依据后缀结尾不同赋予他们不同的资源类型
            // 如以 *.jar结尾的 为RepositoryType.GLOB 类型
            //以 .jar 结尾的 为 RepositoryType.JAR
            //当然还有从配置文件中读取到的URL资源  也一并放入了repositories容器中 类型为RepositoryType.URL
            //
        }
        //将处理完的配置文件后的List 放入类加载器工厂里面
        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * System property replacement in the given string.
     * 将${} 中的内容替换为 配置环境变量的 内容
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        //获取到${在str字符串中的出现为位置
        int pos_start = str.indexOf("${");
        //如果${在字符串中出现过那么post_start就一定大于等于0
        if (pos_start >= 0) {
            //声明一个StringBuilder 对象
            StringBuilder builder = new StringBuilder();
            //设置post_end 默认值为-1  它始终代表下一个}的位置
            int pos_end = -1;
            //只要pos_start还大于零就进行循环
            while (pos_start >= 0) {
                //给StringBuilder添加截取的字符串， 第一次从0截取到1 看样子截取了一个"
                builder.append(str, pos_end + 1, pos_start);
                //改变post_end 的值 从字符串${的下一个字符位(包含下一个字符)往后查找第一个}出现的位置 返回}在字符串中所在位置
                pos_end = str.indexOf('}', pos_start + 2);
                //如果没找到
                if (pos_end < 0) {
                    //pos_end =起始位置减一
                    pos_end = pos_start - 1;
                    //跳出循环
                    break;
                }
                //截取str字符串 从${开始  到}结束  也就是${}里面的内容
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                //如果${}里面是空的
                if (propName.length() == 0) {
                    replacement = null;
                } else if //如果里面写的是catalina
                (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    //那么就设置在初始化阶段 （静态方法中） 配置的资源路径
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                //从pos_end的位置继续向后查找${  赋值给pos_start
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * Initialize daemon.
     *
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {
        // 这个初始化虽然配置了 但因为 lib 下没有相关jar包 相当于啥也没做
        initClassLoaders();
        //2020 年 3月 31 日
        Thread.currentThread().setContextClassLoader(catalinaLoader);
        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        //使用创建好的类加载器加载Catalina这个类
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        //并创建实例
        Object startupInstance = startupClass.getConstructor().newInstance();

        // Set the shared extensions class loader
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        //直接写死一个方法名
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        //获取含有一个参数 参数类型为ClassLoader的 名为setParentClassLoader 的Catalina 类实例的方法
        Method method =
                startupInstance.getClass().getMethod(methodName, paramTypes);
        //调用该实例，并传入一个参数
        method.invoke(startupInstance, paramValues);
        //设置该实例为catalinaDeamon
        catalinaDaemon = startupInstance;
    }


    /**
     * Load daemon.  加载当启动jar包时传入进来的 参数
     */
    private void load(String[] arguments) throws Exception {

        // Call the load() method
        //设置一个 方法名
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        //如果没有传入参数
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            //如果传入了参数
            paramTypes = new Class[1];
            //获取参数的类类型
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            //保存第一个参数
            param[0] = arguments;
        }
        //使用预先声明好的org.apache.catalina.startup.Catalina类的实例，获取可以 启用 这个参数的 load 方法（因为 load可能有重载方法）
        Method method =
                catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled()) {
            log.debug("Calling startup class " + method);
        }
        //使用这个方法开始调用  参数
        method.invoke(catalinaDaemon, param);
    }


    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     *
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    public void init(String[] arguments) throws Exception {

        init();
        load(arguments);
    }


    /**
     * Start the Catalina daemon.
     *
     * @throws Exception Fatal start error
     */
    public void start() throws Exception {
        if (catalinaDaemon == null) {
            init();
        }
        //获取org.apache.catalina.startup.Catalina.Java中start 无参 方法
        Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the Catalina Daemon.
     *
     * @throws Exception Fatal stop error
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the standalone server.
     *
     * @throws Exception Fatal stop error
     */
    public void stopServer() throws Exception {

        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the standalone server.
     *
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    public void stopServer(String[] arguments) throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }


    /**
     * Set flag.
     *
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    public void setAwait(boolean await)
            throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
                catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
                catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    //启动tomcat
    // 在启动main方法前 会装载bootstrap类 加载静态块方法  其目的就是为了设置一些系统 环境变量
    public static void main(String args[]) {
        //防止重复启动项目
        synchronized (daemonLock) {
            //如果daemon 为空 表明 Tomcat 为第一次启动， 如果不为空 表示tomcat已经 启动过一次了
            if (daemon == null) {
                //如果tomcat没有启动过 那么
                Bootstrap bootstrap = new Bootstrap();
                try {
                    bootstrap.init();
                } catch (Throwable t) {
                    handleThrowable(t);
                    t.printStackTrace();
                    return;
                }
                //将新生成的bootstrap设置给 daemon变量
                daemon = bootstrap;
            } else {
                //如果启动过 就使用tomcat
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        try {
            //创建一个命令 名为start
            String command = "start";
            //检查启用Bootstrap启动时 是否有传入参数
            if (args.length > 0) {
                //如果传入了参数 那么 参数长度-1
                command = args[args.length - 1];
            }
            //如果没有传入参数，那么
            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                //如果没有传入参数 直接进入这个  方法  实例的bootstrap开始对参数设置
                daemon.setAwait(true);
                //加载启动 传入的参数
                daemon.load(args);
                //启动org.apache.catalina.startup.Catalina 这个类的 start方式
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     *
     * @return the catalina home
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     *
     * @return the catalina base
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     *
     * @return the catalina home as a file
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     *
     * @return the catalina base as a file
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }


    // Protected for unit testing
    protected static String[] getPaths(String value) {
        //声明一个String List容器
        List<String> result = new ArrayList<>();
        //根据正则表达式
        Matcher matcher = PATH_PATTERN.matcher(value);
        //查找字符串中所有符合的字符串
        while (matcher.find()) {
            //将字符串截取下来
            String path = value.substring(matcher.start(), matcher.end());
            //去掉空格
            path = path.trim();
            //如果截取下来后啥也没有
            if (path.length() == 0) {
                continue;
            }
            //将取出来的字符串 拿到获取第一个字符
            char first = path.charAt(0);
            //将取出来的字符串 拿到最后一个字符
            char last = path.charAt(path.length() - 1);
            //如果第一个字符和最后一个字符是" 并且 取出来的字符串长度大于一
            if (first == '"' && last == '"' && path.length() > 1) {
                //那么 去掉两边的"
                path = path.substring(1, path.length() - 1);
                //然后去掉空格
                path = path.trim();
                //如果没东西了 就继续
                if (path.length() == 0) {
                    continue;
                }
            } //如果字符串中存在\反斜杠
            else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                //直接抛出异常
                throw new IllegalArgumentException(
                        "The double quote [\"] character only be used to quote paths. It must " +
                                "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }
            //将取出来的字符串加入到result中
            result.add(path);
        }
        //将List容器转换成数组返回给方法调用者
        return result.toArray(new String[result.size()]);
    }
}
