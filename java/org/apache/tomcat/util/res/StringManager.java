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
package org.apache.tomcat.util.res;

import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * An internationalization / localization helper class which reduces
 * the bother of handling ResourceBundles and takes care of the
 * common cases of message formatting which otherwise require the
 * creation of Object arrays and such.
 *
 * <p>The StringManager operates on a package basis. One StringManager
 * per package can be created and accessed via the getManager method
 * call.
 *
 * <p>The StringManager will look for a ResourceBundle named by
 * the package name given plus the suffix of "LocalStrings". In
 * practice, this means that the localized information will be contained
 * in a LocalStrings.properties file located in the package
 * directory of the class path.
 *
 * <p>Please see the documentation for java.util.ResourceBundle for
 * more information.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Mel Martinez [mmartinez@g1440.com]
 * @see java.util.ResourceBundle
 * <p>
 * 用于国际化读取配置文件信息的
 */
public class StringManager {

    private static int LOCALE_CACHE_SIZE = 10;

    /**
     * The ResourceBundle for this StringManager.
     * 用于选择加载配置文件信息的类
     */
    private final ResourceBundle bundle;
    //声明当前服务启动时所处于的语言环境，如中文
    private final Locale locale;


    /**
     * Creates a new StringManager for a given package. This is a
     * private method and all access to it is arbitrated by the
     * static getManager method call so that only one StringManager
     * per package will be created.
     * 创建一个StringManager对象通过给的packageName和locale
     * 这是一个私有的方法，他的所有访问通过getManager方法去调用，因此 每个包只会有一个StringManager
     *
     * @param packageName Name of package to create StringManager for.
     */
    private StringManager(String packageName, Locale locale) {
        //创建包名
        String bundleName = packageName + ".LocalStrings";
        //创建选择加载配置文件的 类
        ResourceBundle bnd = null;
        try {
            // The ROOT Locale uses English. If English is requested, force the
            // use of the ROOT Locale else incorrect results may be obtained if
            // the system default locale is not English and translations are
            // available for the system default locale.
            //如果本地环境使用的语言是英语，如果英语是被要求的，force the  那么强制使用他
            //使用Root Locale ，else -if （否则）,obtained(获得) incorrect（错误的）结果或许
            //系统默认的本地语言不是英语，并且translations（翻译）是可以获得的默认区域（当前地区有配置文件）
            if (locale.getLanguage().equals(Locale.ENGLISH.getLanguage())) {
                locale = Locale.ROOT;
            }
            bnd = ResourceBundle.getBundle(bundleName, locale);
        } catch (MissingResourceException ex) {
            // Try from the current loader (that's the case for trusted apps)
            // Should only be required if using a TC5 style classloader structure
            // where common != shared != server
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                try {
                    bnd = ResourceBundle.getBundle(bundleName, locale, cl);
                } catch (MissingResourceException ex2) {
                    // Ignore
                }
            }
        }
        bundle = bnd;
        // Get the actual locale, which may be different from the requested one
        if (bundle != null) {
            Locale bundleLocale = bundle.getLocale();
            if (bundleLocale.equals(Locale.ROOT)) {
                this.locale = Locale.ENGLISH;
            } else {
                this.locale = bundleLocale;
            }
        } else {
            this.locale = null;
        }
    }


    /**
     * Get a string from the underlying resource bundle or return null if the
     * String is not found.
     * 获取一个字符串从资源下面，或者返回一个null如果字符串没有被发现
     *
     * @param key to desired(期望) resource String
     * @return resource String matching <i>key</i> from underlying bundle or
     * null if not found.
     * @throws IllegalArgumentException if <i>key</i> is null
     */
    public String getString(String key) {
        if (key == null) {
            String msg = "key may not have a null value";
            throw new IllegalArgumentException(msg);
        }

        String str = null;

        try {
            // Avoid（避免avoid） NPE if bundle（束、捆，这里是StringManage的一个属性，用于访问国际化资源的） is null and treat it like（把它当作） an MRE
            if (bundle != null) {
                str = bundle.getString(key);
            }
        } catch (MissingResourceException mre) {
            //bad: shouldn't mask an exception the following way:
            //   str = "[cannot find message associated with key '" + key +
            //         "' due to " + mre + "]";
            //     because it hides the fact that the String was missing
            //     from the calling code.
            //good: could just throw the exception (or wrap it in another)
            //      but that would probably cause much havoc on existing
            //      code.
            //better: consistent with container pattern to
            //      simply return null.  Calling code can then do
            //      a null check.
            str = null;
        }

        return str;
    }


    /**
     * Get a string from the underlying resource bundle and format
     * it with the given set of arguments.
     * 获取一个字符串 从bundle资源下面，并且格式化他提供一个绑定参数集
     *
     * @param key  The key for the required message
     * @param args The values to insert into the message
     * @return The request string formatted with the provided arguments or the
     * key if the key was not found.
     */
    public String getString(final String key, final Object... args) {
        String value = getString(key);
        if (value == null) {
            value = key;
        }
        //消息格式，传入从要替换的数据
        MessageFormat mf = new MessageFormat(value);
        //设置本地 locale 在我的电脑上为zh_CN
        mf.setLocale(locale);
        return mf.format(args, new StringBuffer(), null).toString();
    }


    /**
     * Identify the Locale this StringManager is associated with.
     *
     * @return The Locale associated with the StringManager
     */
    public Locale getLocale() {
        return locale;
    }


    // --------------------------------------------------------------
    // STATIC SUPPORT METHODS
    // --------------------------------------------------------------

    private static final Map<String, Map<Locale, StringManager>> managers =
            new Hashtable<>();


    /**
     * Get the StringManager for a given class. The StringManager will be
     * returned for the package in which the class is located. If a manager for
     * that package already exists, it will be reused, else a new
     * StringManager will be created and returned.
     *
     * @param clazz The class for which to retrieve the StringManager
     * @return The instance associated with the package of the provide class
     */
    public static final StringManager getManager(Class<?> clazz) {
        return getManager(clazz.getPackage().getName());
    }


    /**
     * Get the StringManager for a particular package. If a manager for
     * a package already exists, it will be reused, else a new
     * StringManager will be created and returned.
     *
     * @param packageName The package name
     * @return The instance associated with the given package and the default
     * Locale
     */
    public static final StringManager getManager(String packageName) {
        return getManager(packageName, Locale.getDefault());
    }


    /**
     * Get the StringManager for a particular package and Locale. If a manager
     * for a package/Locale combination already exists, it will be reused, else
     * a new StringManager will be created and returned.
     *
     * @param packageName The package name
     * @param locale      The Locale
     * @return The instance associated with the given package and Locale
     * 获取一个StringManager通过指定的包名和Locale，如果 一个驱动，通过package与locale combination（组合）已经存在 。它将被重复使用
     * 否则创建一个新的并且返回
     */
    public static final synchronized StringManager getManager(
            String packageName, Locale locale) {
        //首先在managers里面 取出所有packageName的 语言-StringManager
        Map<Locale, StringManager> map = managers.get(packageName);
        //判断是否存在
        if (map == null) {
            /*
             * Don't want the HashMap to be expanded beyond LOCALE_CACHE_SIZE.
             * Expansion occurs when size() exceeds capacity. Therefore keep
             * size at or below capacity.
             * removeEldestEntry() executes after insertion therefore the test
             * for removal needs to use one less than the maximum desired size
             * dont want （不希望） hashMap expanded（扩充） beyond(超过)LOCALE_CACHE_SIZE
             * Expansion（扩张） occurs (发生) 当size的时候 exceeds（超过） capacity（能力） Therefore（因此） 保持当前容量，或者低于当前支持的容量
             * removeEldestEntry()方法 执行 插入之后，因此 测试移除需要使用一个小于 maximum（渴望；期望）大小
             */
            map = new LinkedHashMap<Locale, StringManager>(LOCALE_CACHE_SIZE, 1, true) {
                private static final long serialVersionUID = 1L;

                //重写了removeEldestEntry方法
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Locale, StringManager> eldest) {
                    if (size() > (LOCALE_CACHE_SIZE - 1)) {
                        return true;
                    }
                    return false;
                }
            };
            managers.put(packageName, map);
        }

        StringManager mgr = map.get(locale);
        if (mgr == null) {
            mgr = new StringManager(packageName, locale);
            map.put(locale, mgr);
        }
        return mgr;
    }


    /**
     * Retrieve the StringManager for a list of Locales. The first StringManager
     * found will be returned.
     *
     * @param packageName      The package for which the StringManager was
     *                         requested
     * @param requestedLocales The list of Locales
     * @return the found StringManager or the default StringManager
     */
    public static StringManager getManager(String packageName,
                                           Enumeration<Locale> requestedLocales) {
        while (requestedLocales.hasMoreElements()) {
            Locale locale = requestedLocales.nextElement();
            StringManager result = getManager(packageName, locale);
            if (result.getLocale().equals(locale)) {
                return result;
            }
        }
        // Return the default
        return getManager(packageName);
    }
}
