/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.webapp.mgt.loader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

public class SharedURLClassLoader extends URLClassLoader {

    private static final Log log = LogFactory.getLog(SharedURLClassLoader.class);

    private WebappClassloadingContext webappCC;


    public SharedURLClassLoader(URL[] array, ClassLoader parent) {

        super(array, parent);
    }

    public void setWebappCC(WebappClassloadingContext classloadingContext) {

        this.webappCC = classloadingContext;
    }


    private URLClassLoader getExclusiveEnvironmentClassloader(String environment) {

        return EnvironmentSharedClassLoader.getSharedClassLoaders(environment);
    }


    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException
    {
        synchronized (getClassLoadingLock(name)) {
            // First, check if the class has already been loaded
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                // 1) Load from the parent if the parent-first is true and if package matches with the
                //    list of delegated packages
                boolean delegatedPkg = webappCC.isDelegatedPackage(name);
                boolean excludedPkg = webappCC.isExcludedPackage(name);

                if (webappCC.isParentFirst() && delegatedPkg && !excludedPkg) {
                    c = super.getParent().loadClass(name);
                    if (c != null) {
                        return c;
                    }
                }

                // 2) Load the class from the shared classloader
                c = findClassEnvironmentalClassLoaders(name, resolve);
                if (c != null) {
                    return c;
                }


                // 4) Load from the parent if the parent-first is false and if the package matches with the
                //    list of delegated packages.
                if (!webappCC.isParentFirst() && delegatedPkg && !excludedPkg) {
                    c = super.getParent().loadClass(name);
                    if (c != null) {
                        return c;
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    protected Class<?> findClassEnvironmentalClassLoaders(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> clazz = null;
        for (String environment : webappCC.getEnvironments()) {
            URLClassLoader loader = getExclusiveEnvironmentClassloader(environment);
            if (log.isDebugEnabled()) {
                log.debug("  Delegating to Environmental classloader " + loader);
            }
            if (loader == null)
                continue;
            try {
                clazz = loader.loadClass(name);
//                clazz = Class.forName(name, false, loader);
                if (clazz != null) {
                    if (log.isDebugEnabled())
                        log.debug("  Loading class from " + loader + " Environmental classloader");
                    if (resolve) {
                        resolveClass(clazz);
                    }
                    return clazz;
                }
            } catch (ClassNotFoundException|NoClassDefFoundError e) {
//            Ignore
            }
        }
        return clazz;
    }

    @Override
    public URL findResource(String name) {

        for (String environment : webappCC.getEnvironments()) {
            URLClassLoader exclusiveEnvironmentClassloader = getExclusiveEnvironmentClassloader(environment);
            if (exclusiveEnvironmentClassloader != null) {
                return exclusiveEnvironmentClassloader.findResource(name);
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {

        for (String environment : webappCC.getEnvironments()) {
            URLClassLoader exclusiveEnvironmentClassloader = getExclusiveEnvironmentClassloader(environment);
            if (exclusiveEnvironmentClassloader != null) {
                Enumeration<URL> url = exclusiveEnvironmentClassloader.findResources(name);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }
}
