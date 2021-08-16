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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * <p>Utility class for building class loaders for Catalina.  The factory
 * method requires the following parameters in order to build a new class
 * loader (with suitable defaults in all cases):</p>
 * <ul>
 * <li>A set of directories containing unpacked classes (and resources)
 *     that should be included in the class loader's
 *     repositories.</li>
 * <li>A set of directories containing classes and resources in JAR files.
 *     Each readable JAR file discovered in these directories will be
 *     added to the class loader's repositories.</li>
 * <li><code>ClassLoader</code> instance that should become the parent of
 *     the new class loader.</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 */
public final class ClassLoaderFactory {


    private static final Log log = LogFactory.getLog(ClassLoaderFactory.class);

    // --------------------------------------------------------- Public Methods


    /**
     * Create and return a new class loader, based on the configuration
     * defaults and the specified directory paths:
     *
     * @param unpacked Array of pathnames to unpacked directories that should
     *  be added to the repositories of the class loader, or <code>null</code>
     * for no unpacked directories to be considered
     * @param packed Array of pathnames to directories containing JAR files
     *  that should be added to the repositories of the class loader,
     * or <code>null</code> for no directories of JAR files to be considered
     * @param parent Parent class loader for the new class loader, or
     *  <code>null</code> for the system class loader.
     * @return the new class loader
     *
     * @exception Exception if an error occurs constructing the class loader
     */
    public static ClassLoader createClassLoader(File unpacked[],
                                                File packed[],
                                                final ClassLoader parent)
        throws Exception {

        if (log.isDebugEnabled())
            log.debug("Creating new class loader");

        // Construct the "class path" for this class loader
        Set<URL> set = new LinkedHashSet<>();

        // Add unpacked directories
        if (unpacked != null) {
            for (int i = 0; i < unpacked.length; i++)  {
                File file = unpacked[i];
                if (!file.canRead())
                    continue;
                file = new File(file.getCanonicalPath() + File.separator);
                URL url = file.toURI().toURL();
                if (log.isDebugEnabled())
                    log.debug("  Including directory " + url);
                set.add(url);
            }
        }

        // Add packed directory JAR files
        if (packed != null) {
            for (int i = 0; i < packed.length; i++) {
                File directory = packed[i];
                if (!directory.isDirectory() || !directory.canRead())
                    continue;
                String filenames[] = directory.list();
                if (filenames == null) {
                    continue;
                }
                for (int j = 0; j < filenames.length; j++) {
                    String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                    if (!filename.endsWith(".jar"))
                        continue;
                    File file = new File(directory, filenames[j]);
                    if (log.isDebugEnabled())
                        log.debug("  Including jar file " + file.getAbsolutePath());
                    URL url = file.toURI().toURL();
                    set.add(url);
                }
            }
        }

        // Construct the class loader itself
        final URL[] array = set.toArray(new URL[set.size()]);
        return AccessController.doPrivileged(
                new PrivilegedAction<URLClassLoader>() {
                    @Override
                    public URLClassLoader run() {
                        if (parent == null)
                            return new URLClassLoader(array);
                        else
                            return new URLClassLoader(array, parent);
                    }
                });
    }


    /**
     * Create and return a new class loader, based on the configuration
     * defaults and the specified directory paths:
     *
     * @param repositories List of class directories, jar files, jar directories
     *                     or URLS that should be added to the repositories of
     *                     the class loader.
     * @param parent Parent class loader for the new class loader, or
     *  <code>null</code> for the system class loader.
     * @return the new class loader
     *
     * @exception Exception if an error occurs constructing the class loader
     */
    public static ClassLoader createClassLoader(List<Repository> repositories,
                                                final ClassLoader parent)
        throws Exception {

        if (log.isDebugEnabled())
            log.debug("Creating new class loader");
        //声明一个URL的Set容器(Set容器不养闲对象)
        Set<URL> set = new LinkedHashSet<>();
        //先确定repositories容器不为空
        if (repositories != null) {
            for (Repository repository : repositories)  {
                //找到catalina.properties中的配置信息 （在repositories 为 URL类型的数据）
                if (repository.getType() == RepositoryType.URL) {
                    URL url = buildClassLoaderUrl(repository.getLocation());
                    if (log.isDebugEnabled())
                        log.debug("  Including URL " + url);
                    //把url丢入向量当中
                    set.add(url);
                }
                //如果repository的类型是文件夹类型
                else if (repository.getType() == RepositoryType.DIR) {
                    //取出路径建立一个File对象
                    File directory = new File(repository.getLocation());
                    //返回此抽象路径名的规范形式。等同于 new File(this.getCanonicalPath()())。
                    directory = directory.getCanonicalFile();
                    //判断当前路径 当前用户是否有访问权限  如果返回false 就跳出此次循环 （当前环境 但前配置 直接跳出了for循环）
                    if (!validateFile(directory, RepositoryType.DIR)) {
                        continue;
                    }
                    //如果存在 的到这个lib 文件 的路径 生成一个URL对象
                    URL url = buildClassLoaderUrl(directory);
                    if (log.isDebugEnabled())
                        log.debug("  Including directory " + url);
                    //存在这个路径就有资格 添加到set容器中  Set
                    set.add(url);
                }
                //如果配置了jar 文件
                else if (repository.getType() == RepositoryType.JAR) {
                    //获取地址 成成一个FIle对象
                    File file=new File(repository.getLocation());
                    //获得File的真实路径
                    file = file.getCanonicalFile();
                    //如果不存在这个Jar包 则 跳出此次循环
                    if (!validateFile(file, RepositoryType.JAR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(file);
                    if (log.isDebugEnabled())
                        log.debug("  Including jar file " + url);
                    //如果顺利丢入Set容器
                    set.add(url);
                }
                //同上
                else if (repository.getType() == RepositoryType.GLOB) {
                    File directory=new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, RepositoryType.GLOB)) {
                        continue;
                    }
                    if (log.isDebugEnabled())
                        log.debug("  Including directory glob "
                            + directory.getAbsolutePath());
                    //列出File 对象下的所有问价名
                    String filenames[] = directory.list();
                    //如果File对象下 没有文件 那么跳出循环
                    if (filenames == null) {
                        continue;
                    }
                    //遍历File对象下的每一个文件
                    for (int j = 0; j < filenames.length; j++) {
                        //将文件名小写
                        String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                        //如果文件下不存在jar包跳出循环
                        if (!filename.endsWith(".jar"))
                            continue;
                        //为存活下来的这个小可爱建立一个File对象
                        File file = new File(directory, filenames[j]);
                        //获取标准路径
                        file = file.getCanonicalFile();
                        //确定存在这个Jar包且 jvm有访问权 否则跳出循环
                        if (!validateFile(file, RepositoryType.JAR)) {
                            continue;
                        }
                        //BB一大堆也不知道干啥
                        if (log.isDebugEnabled())
                            log.debug("    Including glob jar file "
                                + file.getAbsolutePath());
                        // 将File对象转换为URL对象
                        URL url = buildClassLoaderUrl(file);
                        //放入Set容器
                        set.add(url);
                    }
                }
            }
        }
        //将Set容器转换为URL对象数组
        final URL[] array = set.toArray(new URL[set.size()]);
        //日志记录
        if (log.isDebugEnabled())
            for (int i = 0; i < array.length; i++) {
                log.debug("  location " + i + " is " + array[i]);
            }
        // 匿名函数  使用URLClassLoader加载器 将预先准备好的类加载入jvm并返回类加载器实例对象
        //这个地方就是生成一个类加载器，将类加载器要加载的类是array里面的类
        return AccessController.doPrivileged(
                new PrivilegedAction<URLClassLoader>() {
                    @Override
                    public URLClassLoader run() {
                        if (parent == null)
                            //common 传进来的是null
                            return new URLClassLoader(array);
                        else
                            //
                            return new URLClassLoader(array, parent);
                    }
                });
    }

    /**
     *判断传入的File 是否存在 且 可以被JVM访问
     * @param file 传入一个File对象
     * @param type 文件类型描述 “枚举类型”
     * @return  如果存在且可以被访问 就返回ture 反之 false
     * @throws IOException
     */
    private static boolean validateFile(File file,
            RepositoryType type) throws IOException {
        //如果文件类型为DIR或者GLOB类型
        if (RepositoryType.DIR == type || RepositoryType.GLOB == type) {
            //如果文件路径下不是一个文件夹或者 或者java虚拟机没权限读取这个file对象
            //在Tomcat根目录下根本没有lib这个目录所以他不是一个文件夹，也不可以读取
            if (!file.isDirectory() || !file.canRead()) {
                String msg = "Problem with directory [" + file +
                        "], exists: [" + file.exists() +
                        "], isDirectory: [" + file.isDirectory() +
                        "], canRead: [" + file.canRead() + "]";
                //如果lib不是文件夹或者没有权限时Tomcat 开始瞎造目录
                File home = new File (Bootstrap.getCatalinaHome());
                //根据初始化Bootstrap 时候 赋给 catalinaHome 的属性 默认Tomcat 所在路径
                home = home.getCanonicalFile();
                //根据初始化Bootstrap 时候 赋给 catalinaHome 的属性 默认Tomcat 所在路径
                File base = new File (Bootstrap.getCatalinaBase());
                //获取准确的路径（win与 lin平台的 区别）
                base = base.getCanonicalFile();
                //伪造一个Tomcat/lib 对象
                File defaultValue = new File(base, "lib");

                // Existence of ${catalina.base}/lib directory is optional.
                // Hide the warning if Tomcat runs with separate catalina.home
                // and catalina.base and that directory is absent.
                //如果home与base的路径不相等  （在Lenovo中 他是相等的 故不会进入if）
                if (!home.getPath().equals(base.getPath())
                        && file.getPath().equals(defaultValue.getPath())
                        && !file.exists()) {
                    log.debug(msg);
                } else {
                    //日志记录报警 （脱裤子放屁 啥也没干）
                    log.warn(msg);
                }
                return false;
            }
        }
        //如果文件类型为Jar类型  （Lenovo 此项目  同样虽然配置了jar 但是 没有lib目录也没有jar 所以会返回false）
        else if (RepositoryType.JAR == type) {
            if (!file.canRead()) {
                log.warn("Problem with JAR file [" + file +
                        "], exists: [" + file.exists() +
                        "], canRead: [" + file.canRead() + "]");
                return false;
            }
        }
        return true;
    }


    /*
     * These two methods would ideally be in the utility class
     * org.apache.tomcat.util.buf.UriUtil but that class is not visible until
     * after the class loaders have been constructed.
     */
    //传入文件夹路径 返回一个URL
    private static URL buildClassLoaderUrl(String urlString) throws MalformedURLException {
        // URLs passed to class loaders may point to directories that contain
        // JARs. If these URLs are used to construct URLs for resources in a JAR
        // the URL will be used as is. It is therefore necessary to ensure that
        // the sequence "!/" is not present in a class loader URL.
            String result = urlString.replaceAll("!/", "%21/");
        return new URL(result);
    }

    //将File 对象转换为URL对象 (脱裤子放屁)
    private static URL buildClassLoaderUrl(File file) throws MalformedURLException {
        // Could be a directory or a file
        //获取这个file的URL 转成String类型
        String fileUrlString = file.toURI().toString();
        //替换/
        fileUrlString = fileUrlString.replaceAll("!/", "%21/");
        //重新建立一个URL对象
        return new URL(fileUrlString);
    }


    public enum RepositoryType {
        DIR,  //如果是一个文件夹 则 类型为此
        GLOB, //如果是一组jar包 则 类型为此 （团）
        JAR,    //如果是一个jar包则类型为此
        URL
    }

    public static class Repository {
        //文件夹路径
        private final String location;
        //文件夹类型
        private final RepositoryType type;

        public Repository(String location, RepositoryType type) {
            this.location = location;
            this.type = type;
        }

        public String getLocation() {
            return location;
        }

        public RepositoryType getType() {
            return type;
        }
    }
}
