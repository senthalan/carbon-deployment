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

import org.apache.catalina.startup.Bootstrap;
import org.apache.catalina.startup.ClassLoaderFactory;
import org.wso2.carbon.webapp.mgt.DataHolder;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class EnvironmentSharedClassLoader {


    private static Map<String, URLClassLoader> sharedClassLoaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public static void init(ClassloadingConfiguration classloadingConfig) {

        for (String environment : classloadingConfig.getEnvironments()) {
            createClassloader(environment, classloadingConfig.getExclusiveEnvironment(environment), DataHolder.getTccl());
        }

    }

    private static void createClassloader(String name, CLEnvironment environment, ClassLoader parentClassLoader) {

        List<ClassLoaderFactory.Repository> repositories = new ArrayList<>();
        for (String repository : environment.getDelegatedPackageArray()) {
            if (sharedClassLoaders.containsKey(name)) {
                continue;
            }
            if (repository.endsWith("*.jar")) {
                repository = repository.substring(0, repository.length() - "*.jar".length());
                repositories.add(new ClassLoaderFactory.Repository(repository, ClassLoaderFactory.RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new ClassLoaderFactory.Repository(repository, ClassLoaderFactory.RepositoryType.JAR));
            } else if (repository.endsWith("/*")) {
                repository = repository.substring(0, repository.length() - 2);
                repositories.add(new ClassLoaderFactory.Repository(repository, ClassLoaderFactory.RepositoryType.DIR));
            } else {
                repositories.add(new ClassLoaderFactory.Repository(repository, ClassLoaderFactory.RepositoryType.DIR));
            }
        }

        try {
            URLClassLoader classloader = createClassLoader(repositories, parentClassLoader);
            sharedClassLoaders.put(name, classloader);
        } catch (Exception e) {
            //
        }
    }


    private static URLClassLoader createClassLoader(List<ClassLoaderFactory.Repository> repositories,
                                                         final ClassLoader parent)
            throws Exception {


        // Construct the "class path" for this class loader
        Set<URL> set = new LinkedHashSet<>();

        if (repositories != null) {
            for (ClassLoaderFactory.Repository repository : repositories)  {
                if (repository.getType() == ClassLoaderFactory.RepositoryType.URL) {
                    URL url = buildClassLoaderUrl(repository.getLocation());
                    set.add(url);
                } else if (repository.getType() == ClassLoaderFactory.RepositoryType.DIR) {
                    File directory = new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, ClassLoaderFactory.RepositoryType.DIR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(directory);
                    set.add(url);
                } else if (repository.getType() == ClassLoaderFactory.RepositoryType.JAR) {
                    File file=new File(repository.getLocation());
                    file = file.getCanonicalFile();
                    if (!validateFile(file, ClassLoaderFactory.RepositoryType.JAR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(file);
                    set.add(url);
                } else if (repository.getType() == ClassLoaderFactory.RepositoryType.GLOB) {
                    File directory=new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, ClassLoaderFactory.RepositoryType.GLOB)) {
                        continue;
                    }
                    String filenames[] = directory.list();
                    if (filenames == null) {
                        continue;
                    }
                    for (int j = 0; j < filenames.length; j++) {
                        String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                        if (!filename.endsWith(".jar"))
                            continue;
                        File file = new File(directory, filenames[j]);
                        file = file.getCanonicalFile();
                        if (!validateFile(file, ClassLoaderFactory.RepositoryType.JAR)) {
                            continue;
                        }
                        URL url = buildClassLoaderUrl(file);
                        set.add(url);
                    }
                }
            }
        }

        // Construct the class loader itself
        final URL[] array = set.toArray(new URL[set.size()]);


        return AccessController.doPrivileged(
                (PrivilegedAction<URLClassLoader>) () -> new URLClassLoader(array, parent));
    }

    private static boolean validateFile(File file,
                                        ClassLoaderFactory.RepositoryType type) throws IOException {
        if (ClassLoaderFactory.RepositoryType.DIR == type || ClassLoaderFactory.RepositoryType.GLOB == type) {
            if (!file.isDirectory() || !file.canRead()) {
                String msg = "Problem with directory [" + file +
                        "], exists: [" + file.exists() +
                        "], isDirectory: [" + file.isDirectory() +
                        "], canRead: [" + file.canRead() + "]";

                File home = new File (Bootstrap.getCatalinaHome());
                home = home.getCanonicalFile();
                File base = new File (Bootstrap.getCatalinaBase());
                base = base.getCanonicalFile();
                File defaultValue = new File(base, "lib");

                // Existence of ${catalina.base}/lib directory is optional.
                // Hide the warning if Tomcat runs with separate catalina.home
                // and catalina.base and that directory is absent.
                return false;
            }
        } else if (ClassLoaderFactory.RepositoryType.JAR == type) {
            if (!file.canRead()) {
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
    private static URL buildClassLoaderUrl(String urlString) throws MalformedURLException {
        // URLs passed to class loaders may point to directories that contain
        // JARs. If these URLs are used to construct URLs for resources in a JAR
        // the URL will be used as is. It is therefore necessary to ensure that
        // the sequence "!/" is not present in a class loader URL.
        String result = urlString.replaceAll("!/", "%21/");
        return new URL(result);
    }


    private static URL buildClassLoaderUrl(File file) throws MalformedURLException {
        // Could be a directory or a file
        String fileUrlString = file.toURI().toString();
        fileUrlString = fileUrlString.replaceAll("!/", "%21/");
        return new URL(fileUrlString);
    }

    public static URLClassLoader getSharedClassLoaders(String name){
        return sharedClassLoaders.get(name);
    }

}
