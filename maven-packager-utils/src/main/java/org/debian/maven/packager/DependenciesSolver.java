package org.debian.maven.packager;

/*
 * Copyright 2009 Ludovic Claude.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.stream.XMLStreamException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.debian.maven.repo.*;

/**
 * Analyze the Maven dependencies and extract the Maven rules to use
 * as well as the list of dependent packages.
 *
 * @author Ludovic Claude
 */
public class DependenciesSolver {

    private static final Logger log = Logger.getLogger(DependenciesSolver.class.getName());

    // Plugins not useful for the build or whose use is against the
    // Debian policy
    private static final String[][] PLUGINS_TO_IGNORE = {
        {"org.apache.maven.plugins", "maven-ant-plugin"},
        {"org.apache.maven.plugins", "maven-archetype-plugin"},
        {"org.apache.maven.plugins", "changelog-maven-plugin"},
        {"org.apache.maven.plugins", "maven-deploy-plugin"},
        {"org.apache.maven.plugins", "maven-release-plugin"},
        {"org.apache.maven.plugins", "maven-remote-resources-plugin"},
        {"org.apache.maven.plugins", "maven-repository-plugin"},
        {"org.apache.maven.plugins", "maven-scm-plugin"},
        {"org.apache.maven.plugins", "maven-stage-plugin"},
        {"org.apache.maven.plugins", "maven-eclipse-plugin"},
        {"org.apache.maven.plugins", "maven-idea-plugin"},
        {"org.apache.maven.plugins", "maven-source-plugin"},
        {"org.codehaus.mojo", "changelog-maven-plugin"},
        {"org.codehaus.mojo", "netbeans-freeform-maven-plugin"},
        {"org.codehaus.mojo", "nbm-maven-plugin"},
        {"org.codehaus.mojo", "ideauidesigner-maven-plugin"},
        {"org.codehaus.mojo", "scmchangelog-maven-plugin"},};
    private static final String[][] PLUGINS_THAT_CAN_BE_IGNORED = {
        {"org.apache.maven.plugins", "maven-assembly-plugin"},
        {"org.codehaus.mojo", "buildnumber-maven-plugin"},
        {"org.apache.maven.plugins", "maven-verifier-plugin"},
        {"org.codehaus.mojo", "findbugs-maven-plugin"},
        {"org.codehaus.mojo", "fitnesse-maven-plugin"},
        {"org.codehaus.mojo", "selenium-maven-plugin"},
        {"org.codehaus.mojo", "dbunit-maven-plugin"},
        {"org.codehaus.mojo", "failsafe-maven-plugin"},
        {"org.codehaus.mojo", "shitty-maven-plugin"},};
    private static final String[][] DOC_PLUGINS = {
        {"org.apache.maven.plugins", "maven-changelog-plugin"},
        {"org.apache.maven.plugins", "maven-changes-plugin"},
        {"org.apache.maven.plugins", "maven-checkstyle-plugin"},
        {"org.apache.maven.plugins", "maven-clover-plugin"},
        {"org.apache.maven.plugins", "maven-docck-plugin"},
        {"org.apache.maven.plugins", "maven-javadoc-plugin"},
        {"org.apache.maven.plugins", "maven-jxr-plugin"},
        {"org.apache.maven.plugins", "maven-pmd-plugin"},
        {"org.apache.maven.plugins", "maven-project-info-reports-plugin"},
        {"org.apache.maven.plugins", "maven-surefire-report-plugin"},
        {"org.apache.maven.plugins", "maven-pdf-plugin"},
        {"org.apache.maven.plugins", "maven-site-plugin"},
        {"org.codehaus.mojo", "changes-maven-plugin"},
        {"org.codehaus.mojo", "clirr-maven-plugin"},
        {"org.codehaus.mojo", "cobertura-maven-plugin"},
        {"org.codehaus.mojo", "taglist-maven-plugin"},
        {"org.codehaus.mojo", "dita-maven-plugin"},
        {"org.codehaus.mojo", "docbook-maven-plugin"},
        {"org.codehaus.mojo", "javancss-maven-plugin"},
        {"org.codehaus.mojo", "jdepend-maven-plugin"},
        {"org.codehaus.mojo", "jxr-maven-plugin"},
        {"org.codehaus.mojo", "dashboard-maven-plugin"},
        {"org.codehaus.mojo", "emma-maven-plugin"},
        {"org.codehaus.mojo", "sonar-maven-plugin"},
        {"org.jboss.maven.plugins", "maven-jdocbook-plugin"},
    };
    private static final String[][] TEST_PLUGINS = {
        {"org.apache.maven.plugins", "maven-failsafe-plugin"},
        {"org.apache.maven.plugins", "maven-surefire-plugin"},
        {"org.apache.maven.plugins", "maven-verifier-plugin"},
        {"org.codehaus.mojo", "findbugs-maven-plugin"},
        {"org.codehaus.mojo", "fitnesse-maven-plugin"},
        {"org.codehaus.mojo", "selenium-maven-plugin"},
        {"org.codehaus.mojo", "dbunit-maven-plugin"},
        {"org.codehaus.mojo", "failsafe-maven-plugin"},
        {"org.codehaus.mojo", "shitty-maven-plugin"},};
    private static final String[][] EXTENSIONS_TO_IGNORE = {
        {"org.apache.maven.wagon", "wagon-ssh"},
        {"org.apache.maven.wagon", "wagon-ssh-external"},
        {"org.apache.maven.wagon", "wagon-ftp"},
        {"org.apache.maven.wagon", "wagon-http"},
        {"org.apache.maven.wagon", "wagon-http-lightweight"},
        {"org.apache.maven.wagon", "wagon-scm"},
        {"org.apache.maven.wagon", "wagon-webdav"},
        {"org.jvnet.wagon-svn", "wagon-svn"},
    };

    protected File baseDir;
    protected POMTransformer pomTransformer = new POMTransformer();
    protected File outputDirectory;
    protected String packageName;
    protected String packageType;
    protected File mavenRepo = new File("/usr/share/maven-repo");
    protected boolean exploreProjects;
    protected List projects = new ArrayList();
    private Repository repository;
    private List issues = new ArrayList();
    private List projectPoms = new ArrayList();
    private List toResolve = new ArrayList();
    private Set knownProjectDependencies = new HashSet();
    private Set ignoredDependencies = new TreeSet();
    private Set compileDepends = new TreeSet();
    private Set testDepends = new TreeSet();
    private Set runtimeDepends = new TreeSet();
    private Set optionalDepends = new TreeSet();
    private DependencyRuleSet cleanIgnoreRules = new DependencyRuleSet("Ignore rules to be applied during the Maven clean phase",
            new File("debian/maven.cleanIgnoreRules"));
    private boolean checkedAptFile;
    private boolean runTests;
    private boolean generateJavadoc;
    private boolean nonInteractive;
    private boolean askedToFilterModules = false;
    private boolean filterModules = false;

    public DependenciesSolver() {
        pomTransformer.setVerbose(true);
        pomTransformer.getRules().setDescription(readResource("maven.rules.description"));
        pomTransformer.getIgnoreRules().setDescription(readResource("maven.ignoreRules.description"));
        pomTransformer.getPublishedRules().setDescription(readResource("maven.publishedRules.description"));
        cleanIgnoreRules.setDescription(readResource("maven.cleanIgnoreRules.description"));
    }

    private static String readResource(String resource) {
        StringBuffer sb = new StringBuffer();
        try {
            InputStream is = DependenciesSolver.class.getResourceAsStream("/" + resource);
            LineNumberReader r = new LineNumberReader(new InputStreamReader(is));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
                sb.append("\n");
            }
            r.close();
        } catch (IOException e) {
            log.log(Level.SEVERE, "Cannot read resource " + resource, e);
        }
        return sb.toString();
    }

    public void setRunTests(boolean b) {
        this.runTests = b;
    }

    private void setGenerateJavadoc(boolean b) {
        this.generateJavadoc = b;
    }

    private boolean containsPlugin(String[][] pluginDefinitions, Dependency plugin) {
        for (int i = 0; i < pluginDefinitions.length; i++) {
            if (!plugin.getGroupId().equals(pluginDefinitions[i][0])) {
                continue;
            }
            if (plugin.getArtifactId().equals(pluginDefinitions[i][1])) {
                return true;
            }
        }
        return false;
    }

    private boolean isDocumentationOrReportPlugin(Dependency dependency) {
        return containsPlugin(DOC_PLUGINS, dependency);
    }

    private boolean isTestPlugin(Dependency dependency) {
        return containsPlugin(TEST_PLUGINS, dependency);
    }

    private boolean isDefaultMavenPlugin(Dependency dependency) {
        if (getRepository() != null && getRepository().getSuperPOM() != null) {
            for (Iterator i = getRepository().getSuperPOM().getPluginManagement().iterator(); i.hasNext();) {
                Dependency defaultPlugin = (Dependency) i.next();
                if (defaultPlugin.equalsIgnoreVersion(dependency)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canIgnorePlugin(Dependency dependency) {
        return containsPlugin(PLUGINS_TO_IGNORE, dependency);
    }

    private boolean canIgnoreExtension(Dependency dependency) {
        return containsPlugin(EXTENSIONS_TO_IGNORE, dependency);
    }

    private boolean canBeIgnoredPlugin(Dependency dependency) {
        return containsPlugin(PLUGINS_THAT_CAN_BE_IGNORED, dependency);
    }

    private boolean askIgnoreDependency(String sourcePomLoc, Dependency dependency, String message) {
        return askIgnoreDependency(sourcePomLoc, dependency, message, true);
    }

    private boolean askIgnoreDependency(String sourcePomLoc, Dependency dependency, String message, boolean defaultToIgnore) {
        if (nonInteractive) {
            return false;
        }
        System.out.println();
        System.out.println("In " + sourcePomLoc + ":");
        System.out.println(message);
        System.out.println("  " + dependency);
        if (defaultToIgnore) {
            System.out.print("[y]/n > ");
        } else {
            System.out.print("y/[n] > ");
        }
        String s = readLine().trim().toLowerCase();
        if (defaultToIgnore) {
            return !s.startsWith("n");
        } else {
            return s.startsWith("y");
        }
    }

    public void setNonInteractive(boolean nonInteractive) {
        this.nonInteractive = nonInteractive;
    }

    private class ToResolve {

        private final File sourcePom;
        private final String listType;
        private final boolean buildTime;
        private final boolean mavenExtension;
        private final boolean management;

        private ToResolve(File sourcePom, String listType, boolean buildTime, boolean mavenExtension, boolean management) {
            this.sourcePom = sourcePom;
            this.listType = listType;
            this.buildTime = buildTime;
            this.mavenExtension = mavenExtension;
            this.management = management;
        }

        public void resolve() {
            try {
                resolveDependencies(sourcePom, listType, buildTime, mavenExtension, management);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Cannot resolve dependencies on " + sourcePom, e);
            }
        }
    }

    public File getBaseDir() {
        return baseDir;
    }

    public void saveListOfPoms() {
        pomTransformer.getListOfPOMs().save();
    }

    public void saveMavenRules() {
        pomTransformer.getRules().save();
    }

    public void saveMavenPublishedRules() {
        pomTransformer.getPublishedRules().save();
    }

    public void saveMavenIgnoreRules() {
        pomTransformer.getIgnoreRules().save();
    }

    public void saveMavenCleanIgnoreRules() {
        cleanIgnoreRules.save();
    }

    public void saveSubstvars() {
        File dependencies = new File(outputDirectory, packageName + ".substvars");
        Properties depVars = new Properties();
        if (dependencies.exists()) {
            try {
                depVars.load(new FileReader(dependencies));
            } catch (IOException ex) {
                log.log(Level.SEVERE, "Error while reading file " + dependencies, ex);
            }
        }
        depVars.put("maven.CompileDepends", toString(compileDepends));
        depVars.put("maven.TestDepends", toString(testDepends));
        depVars.put("maven.Depends", toString(runtimeDepends));
        depVars.put("maven.OptionalDepends", toString(optionalDepends));
        Set docRuntimeDepends = new TreeSet();
        docRuntimeDepends.add("default-jdk-doc");
        for (Iterator i = runtimeDepends.iterator(); i.hasNext();) {
            docRuntimeDepends.add(i.next() + "-doc");
        }
        Set docOptionalDepends = new TreeSet();
        for (Iterator i = optionalDepends.iterator(); i.hasNext();) {
            docOptionalDepends.add(i.next() + "-doc");
        }
        depVars.put("maven.DocDepends", toString(docRuntimeDepends));
        depVars.put("maven.DocOptionalDepends", toString(docOptionalDepends));
        try {
            depVars.store(new FileOutputStream(dependencies), "List of dependencies for " + packageName + ", generated for use by debian/control");
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Error while saving file " + dependencies, ex);
        }
    }

    public void setBaseDir(File baseDir) {
        this.baseDir = baseDir;
    }

    public void setListOfPoms(File listOfPoms) {
        if (pomTransformer.getListOfPOMs() == null) {
            pomTransformer.setListOfPOMs(new ListOfPOMs(listOfPoms));
        } else {
            pomTransformer.getListOfPOMs().setListOfPOMsFile(listOfPoms);
        }
    }

    public boolean isExploreProjects() {
        return exploreProjects;
    }

    public void setExploreProjects(boolean exploreProjects) {
        this.exploreProjects = exploreProjects;
    }

    public File getMavenRepo() {
        return mavenRepo;
    }

    public void setMavenRepo(File mavenRepo) {
        this.mavenRepo = mavenRepo;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
        pomTransformer.getRules().setRulesFile(new File(outputDirectory, "maven.rules"));
        pomTransformer.getIgnoreRules().setRulesFile(new File(outputDirectory, "maven.ignoreRules"));
        pomTransformer.getPublishedRules().setRulesFile(new File(outputDirectory, "maven.publishedRules"));
        cleanIgnoreRules.setRulesFile(new File(outputDirectory, "maven.cleanIgnoreRules"));
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getPackageType() {
        return packageType;
    }

    public void setPackageType(String packageType) {
        this.packageType = packageType;
    }

    public List getProjects() {
        return projects;
    }

    public void setProjects(List projects) {
        this.projects = projects;
    }

    public List getIssues() {
        return issues;
    }

    private Repository getRepository() {
        if (repository == null && mavenRepo != null) {
            repository = new Repository(mavenRepo);
            repository.scan();
        }
        return repository;
    }

    public void solveDependencies() {
        pomTransformer.setRepository(getRepository());
        pomTransformer.usePluginVersionsFromRepository();

        File f = outputDirectory;
        if (!f.exists()) {
            f.mkdirs();
        }

        if (exploreProjects) {
            File pom = new File(baseDir, "pom.xml");
            if (pom.exists()) {
                projects.add(pom);
            } else {
                pom = new File(baseDir, "debian/pom.xml");
                if (pom.exists()) {
                    projects.add(pom);
                } else {
                    System.err.println("Cannot find the POM file");
                    return;
                }
            }
            resolveDependencies(pom);
        } else {
            for (Iterator i = projects.iterator(); i.hasNext();) {
                File pom = (File) i.next();
                resolveDependencies(pom);
            }
        }

        resolveDependenciesNow();

        if (!issues.isEmpty()) {
            System.err.println("ERROR:");
            for (Iterator i = issues.iterator(); i.hasNext();) {
                String issue = (String) i.next();
                System.err.println(issue);
            }
            System.err.println("--------");
        }
    }

    private void resolveDependencies(File projectPom) {
        String pomRelPath = projectPom.getAbsolutePath().substring(baseDir.getAbsolutePath().length() + 1);
        boolean noParent = false;

        try {
            POMInfo pom = getPOM(projectPom);
            pom.setProperties(new HashMap());
            pom.getProperties().put("debian.package", getPackageName());
//            System.out.println("Register POM " + pom.getThisPom().getGroupId() + ":" + pom.getThisPom().getArtifactId()
//                    + ":" + pom.getThisPom().getVersion());
            try {
                getRepository().registerPom(projectPom, pom);
            } catch (DependencyNotFoundException e) {
                System.out.println("Cannot find parent dependency " + e.getDependency());
                if (!nonInteractive) {
                    noParent = askIgnoreDependency(pomRelPath, pom.getParent(), "Ignore the parent POM for this POM?");
                    if (noParent) {
                        pom.setParent(null);
                        try {
                            getRepository().registerPom(projectPom, pom);
                        } catch (DependencyNotFoundException e1) {
                            // ignore
                        }
                    }
                }
            }

            knownProjectDependencies.add(pom.getThisPom());

            if (filterModules) {
                System.out.println("Include the module " + pomRelPath + " ?");
                System.out.print("[y]/n > ");
                String s = readLine().trim().toLowerCase();
                boolean includeModule = !s.startsWith("n");
                if (!includeModule) {
                    ListOfPOMs.POMOptions options = pomTransformer.getListOfPOMs().getOrCreatePOMOptions(pomRelPath);
                    options.setIgnore(true);
                    String type = "*";
                    if (pom.getThisPom().getType() != null) {
                        type = pom.getThisPom().getType();
                    }
                    String rule = pom.getThisPom().getGroupId() + " " + pom.getThisPom().getArtifactId()
                            + " " + type + " *";
                    pomTransformer.getIgnoreRules().add(new DependencyRule(rule));
                    return;
                }
            }

            if (pom.getParent() != null) {
                POMInfo parentPom = getRepository().searchMatchingPOM(pom.getParent());
                if (parentPom == null || parentPom.equals(getRepository().getSuperPOM())) {
                    noParent = true;
                }
                if (!baseDir.equals(projectPom.getParentFile())) {
//                    System.out.println("Checking the parent dependency in the sub project " + projectPom);
                    resolveDependenciesLater(projectPom, POMInfo.PARENT, false, false, false);
                }
            }

            projectPoms.add(pom.getThisPom());
            pomTransformer.getListOfPOMs().getOrCreatePOMOptions(pomRelPath).setNoParent(noParent);

            resolveDependenciesLater(projectPom, POMInfo.DEPENDENCIES, false, false, false);
            resolveDependenciesLater(projectPom, POMInfo.DEPENDENCY_MANAGEMENT_LIST, false, false, true);
            resolveDependenciesLater(projectPom, POMInfo.PLUGINS, true, true, false);
            resolveDependenciesLater(projectPom, POMInfo.PLUGIN_DEPENDENCIES, true, true, false);
            resolveDependenciesLater(projectPom, POMInfo.PLUGIN_MANAGEMENT, true, true, true);
            resolveDependenciesLater(projectPom, POMInfo.REPORTING_PLUGINS, true, true, false);
            resolveDependenciesLater(projectPom, POMInfo.EXTENSIONS, true, true, false);

            if (exploreProjects && !pom.getModules().isEmpty()) {
                if (!nonInteractive && !askedToFilterModules) {
                    System.out.println("This project contains modules. Include all modules?");
                    System.out.print("[y]/n > ");
                    String s = readLine().trim().toLowerCase();
                    filterModules = s.startsWith("n");
                    askedToFilterModules = true;
                }
                for (Iterator i = pom.getModules().iterator(); i.hasNext();) {
                    String module = (String) i.next();
                    File modulePom = new File(projectPom.getParent(), module + "/pom.xml");
                    resolveDependencies(modulePom);
                }
            }
        } catch (Exception ex) {
            log.log(Level.SEVERE, "Error while resolving " + projectPom, ex);
        }
    }

    private POMInfo getPOM(File projectPom) throws XMLStreamException, IOException {
        File tmpDest = File.createTempFile("pom", ".tmp");
        tmpDest.deleteOnExit();
        return pomTransformer.transformPom(projectPom, tmpDest);
    }

    private String readLine() {
        LineNumberReader consoleReader = new LineNumberReader(new InputStreamReader(System.in));
        try {
            return consoleReader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    private void resolveDependenciesNow() {
        for (Iterator i = toResolve.iterator(); i.hasNext();) {
            ToResolve tr = (ToResolve) i.next();
            tr.resolve();
            i.remove();
        }
    }

    private void resolveDependenciesLater(File sourcePom, String listType, boolean buildTime, boolean mavenExtension, boolean management) {
        toResolve.add(new ToResolve(sourcePom, listType, buildTime, mavenExtension, management));
    }

    private void resolveDependencies(File sourcePom, String listType, boolean buildTime, boolean mavenExtension, boolean management) throws Exception {
        String sourcePomLoc = sourcePom.getAbsolutePath();
        String baseDirPath = baseDir.getAbsolutePath();
        sourcePomLoc = sourcePomLoc.substring(baseDirPath.length() + 1, sourcePomLoc.length());

        List poms = getPOM(sourcePom).getDependencyList(listType);

        nextDependency:
        for (Iterator i = poms.iterator(); i.hasNext();) {
            Dependency dependency = (Dependency) i.next();
            if (containsDependencyIgnoreVersion(ignoredDependencies, dependency) ||
                containsDependencyIgnoreVersion(knownProjectDependencies, dependency) ||
                    (management && isDefaultMavenPlugin(dependency))) {
                continue;
            }

            boolean ignoreDependency = false;
            if (canIgnorePlugin(dependency)) {
                ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "This plugin is not useful for the build or its use is against Debian policies. Ignore this plugin?");
            } else if (canIgnoreExtension(dependency)) {
                ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "This extension is not useful for the build or its use is against Debian policies. Ignore this extension?");
            } else if (canBeIgnoredPlugin(dependency)) {
                ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "This plugin may be ignored in some cases. Ignore this plugin?");
            } else if (!runTests) {
                if ("test".equals(dependency.getScope())) {
                    ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "Tests are turned off. Ignore this test dependency?");
                } else if (isTestPlugin(dependency)) {
                    ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "Tests are turned off. Ignore this test plugin?");
                }
            } else if (!generateJavadoc && isDocumentationOrReportPlugin(dependency)) {
                ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "Documentation is turned off. Ignore this documentation plugin?");
            }

            if (ignoreDependency) {
                ignoredDependencies.add(dependency);
                String ruleDef = dependency.getGroupId() + " " + dependency.getArtifactId() + " * *";
                pomTransformer.getIgnoreRules().add(new DependencyRule(ruleDef));
                continue;
            }

            POMInfo pom = getRepository().searchMatchingPOM(dependency);
            if (pom == null && dependency.getVersion() == null) {
                // Set a dummy version and try again
                for (int version = 0; version < 10; version++) {
                    dependency.setVersion(version + ".0");
                    pom = getRepository().searchMatchingPOM(dependency);
                    if (pom != null) {
                        break;
                    }
                }
            }
            if (pom == null && "maven-plugin".equals(dependency.getType())) {
                List matchingPoms = getRepository().searchMatchingPOMsIgnoreVersion(dependency);
                if (matchingPoms.size() > 1) {
                    issues.add(sourcePomLoc + ": More than one version matches the plugin " + dependency.getGroupId() + ":"
                            + dependency.getArtifactId() + ":" + dependency.getVersion());
                }
                if (!matchingPoms.isEmpty()) {
                    pom = (POMInfo) matchingPoms.get(0);
                    // Don't add a rule to force the version of a Maven plugin, it's now done
                    // automatically at build time
                }
            }
            if (pom == null) {
                if (!management) {
                    if ("maven-plugin".equals(dependency.getType()) && packageType.equals("ant")) {
                        ignoreDependency = true;
                    }
                    if (!ignoreDependency && isDocumentationOrReportPlugin(dependency)) {
                        ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency,
                                "This documentation or report plugin cannot be found in the Maven repository for Debian. Ignore this plugin?");
                    }
                    if (!ignoreDependency) {
                        if ("maven-plugin".equals(dependency.getType())) {
                            issues.add(sourcePomLoc + ": Plugin is not packaged in the Maven repository for Debian: " + dependency.getGroupId() + ":"
                                    + dependency.getArtifactId() + ":" + dependency.getVersion());
                            ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "This plugin cannot be found in the Debian Maven repository. Ignore this plugin?", false);
                        } else {
                            issues.add(sourcePomLoc + ": Dependency is not packaged in the Maven repository for Debian: " + dependency.getGroupId() + ":"
                                    + dependency.getArtifactId() + ":" + dependency.getVersion());
                            ignoreDependency = askIgnoreDependency(sourcePomLoc, dependency, "This dependency cannot be found in the Debian Maven repository. Ignore this dependency?", false);
                        }
                    }
                    if (ignoreDependency) {
                        ignoredDependencies.add(dependency);
                        String ruleDef = dependency.getGroupId() + " " + dependency.getArtifactId() + " * *";
                        pomTransformer.getIgnoreRules().add(new DependencyRule(ruleDef));
                        continue;
                    }
                }

                return;
            }

            // Handle the case of Maven plugins built and used in a multi-module build:
            // they need to be added to maven.cleanIgnoreRules to avoid errors during
            // a mvn clean
            if ("maven-plugin".equals(dependency.getType()) && containsDependencyIgnoreVersion(projectPoms, dependency)) {
                String ruleDef = dependency.getGroupId() + " " + dependency.getArtifactId() + " maven-plugin *";
                cleanIgnoreRules.add(new DependencyRule(ruleDef));
            }

            // Discover the library to import for the dependency
            String library = null;
            if (pom.getProperties() != null) {
                library = (String) pom.getProperties().get("debian.package");
            }
            if (library == null) {
                issues.add(sourcePomLoc + ": Dependency is missing the Debian properties in its POM: " + dependency.getGroupId() + ":"
                        + dependency.getArtifactId() + ":" + dependency.getVersion());
                File pomFile = new File(mavenRepo, dependency.getGroupId().replace(".", "/") + "/" + dependency.getArtifactId() + "/" + dependency.getVersion() + "/" + dependency.getArtifactId() + "-" + dependency.getVersion() + ".pom");
                library = searchPkg(pomFile);
            }
            if (library != null && !library.equals(getPackageName())) {
                if (buildTime) {
                    if ("test".equals(dependency.getScope())) {
                        testDepends.add(library);
                    } else if ("maven-plugin".equals(dependency.getType())) {
                        if (!packageType.equals("ant")) {
                            compileDepends.add(library + " (>= " + pom.getOriginalVersion() + ")");
                        }
                    } else if (mavenExtension) {
                        if (!packageType.equals("ant")) {
                            compileDepends.add(library);
                        }
                    } else {
                        compileDepends.add(library);
                    }
                } else {
                    if (dependency.isOptional()) {
                        optionalDepends.add(library);
                    } else if ("test".equals(dependency.getScope())) {
                        testDepends.add(library);
                    } else {
                        runtimeDepends.add(library);
                    }
                }
            }
            String mavenRules = (String) pom.getProperties().get("debian.mavenRules");
            if (mavenRules != null) {
                StringTokenizer st = new StringTokenizer(mavenRules, ",");
                while (st.hasMoreTokens()) {
                    String ruleDef = st.nextToken().trim();
                    pomTransformer.getRules().add(new DependencyRule(ruleDef));
                }
            }
        }
    }

    private boolean containsDependencyIgnoreVersion(Collection dependencies, Dependency dependency) {
        for (Iterator j = dependencies.iterator(); j.hasNext();) {
            Dependency ignoredDependency = (Dependency) j.next();
            if (ignoredDependency.equalsIgnoreVersion(dependency)) {
                return true;
            }
        }
        return false;
    }

    private String searchPkg(File pomFile) {
        GetPackageResult packageResult = new GetPackageResult();
        executeProcess(new String[]{"dpkg", "--search", pomFile.getAbsolutePath()}, packageResult);
        if (packageResult.getResult() != null) {
            return packageResult.getResult();
        }

        if (!checkedAptFile) {
            if (!"maven2".equals(searchPkg(new File("/usr/bin/mvn")))) {
                System.err.println("Warning: apt-file doesn't seem to be configured");
                System.err.println("Please run the following command and start again:");
                System.err.println("  sudo apt-file update");
                return null;
            }
            checkedAptFile = true;
        }
        executeProcess(new String[]{"apt-file", "search", pomFile.getAbsolutePath()}, packageResult);
        return packageResult.getResult();
    }

    public static void executeProcess(final String[] cmd, final OutputHandler handler) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            System.out.print("> ");
            for (int i = 0; i < cmd.length; i++) {
                String arg = cmd[i];
                System.out.print(arg + " ");
            }
            System.out.println();
            final Process process = pb.start();
            try {
                ThreadFactory threadFactory = new ThreadFactory() {

                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "Run command " + cmd[0]);
                        t.setDaemon(true);
                        return t;
                    }
                };

                ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
                executor.execute(new Runnable() {

                    public void run() {
                        try {
                            InputStreamReader isr = new InputStreamReader(process.getInputStream());
                            BufferedReader br = new BufferedReader(isr);
                            LineNumberReader aptIn = new LineNumberReader(br);
                            String line;
                            while ((line = aptIn.readLine()) != null) {
                                System.out.println(line);
                                handler.newLine(line);
                            }
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    }
                });

                process.waitFor();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                if (process.exitValue() == 0) {
                } else {
                    System.out.println("Cannot execute " + cmd[0]);
                }
                process.destroy();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.interrupted();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private String toString(Set s) {
        StringBuffer sb = new StringBuffer();
        for (Iterator i = s.iterator(); i.hasNext();) {
            String st = (String) i.next();
            sb.append(st);
            if (i.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    public static interface OutputHandler {

        void newLine(String line);
    }

    public static class NoOutputHandler implements OutputHandler {

        public void newLine(String line) {
        }
    }

    static class GetPackageResult implements OutputHandler {

        private String result;

        public void newLine(String line) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.indexOf(' ') > colon) {
                result = line.substring(0, colon);
                // Ignore lines such as 'dpkg : xxx'
                if (!result.equals(result.trim()) || result.startsWith("dpkg")) {
                    result = null;
                } else {
                    System.out.println("Found " + result);
                }
            }
        }

        public String getResult() {
            return result;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            System.out.println("Purpose: Solve the dependencies in the POM(s).");
            System.out.println("Usage: [option]");
            System.out.println("");
            System.out.println("Options:");
            System.out.println("  -v, --verbose: be extra verbose");
            System.out.println("  -p<package>, --package=<package>: name of the Debian package containing");
            System.out.println("    this library");
//            System.out.println("  -r<rules>, --rules=<rules>: path to the file containing the");
//            System.out.println("    extra rules to apply when cleaning the POM");
//            System.out.println("  -i<rules>, --published-rules=<rules>: path to the file containing the");
//            System.out.println("    extra rules to publish in the property debian.mavenRules in the cleaned POM");
            System.out.println("  --ant: use ant for the packaging");
            System.out.println("  --run-tests: run the unit tests");
            System.out.println("  --generate-javadoc: generate Javadoc");
            System.out.println("  --non-interactive: non interactive session");
            return;
        }
        DependenciesSolver solver = new DependenciesSolver();

        solver.setBaseDir(new File("."));
        solver.setExploreProjects(true);
        solver.setOutputDirectory(new File("debian"));

        int i = inc(-1, args);
        boolean verbose = false;
        String debianPackage = "";
        String packageType = "maven";
        while (i < args.length && (args[i].trim().startsWith("-") || args[i].trim().isEmpty())) {
            String arg = args[i].trim();
            if ("--verbose".equals(arg) || "-v".equals(arg)) {
                verbose = true;
            } else if (arg.startsWith("-p")) {
                debianPackage = arg.substring(2);
            } else if (arg.startsWith("--package=")) {
                debianPackage = arg.substring("--package=".length());
            } else if (arg.equals("--ant")) {
                packageType = "ant";
            } else if (arg.equals("--run-tests")) {
                solver.setRunTests(true);
            } else if (arg.equals("--generate-javadoc")) {
                solver.setGenerateJavadoc(true);
            } else if (arg.equals("--non-interactive")) {
                solver.setNonInteractive(true);
            }
            i = inc(i, args);
        }
        File poms = new File(solver.getOutputDirectory(), debianPackage + ".poms");

        solver.setPackageName(debianPackage);
        solver.setPackageType(packageType);
        solver.setExploreProjects(true);
        solver.setListOfPoms(poms);

        if (verbose) {
            System.out.println("Solving dependencies for package " + debianPackage);
        }

        solver.solveDependencies();

        solver.saveListOfPoms();
        solver.saveMavenRules();
        solver.saveMavenIgnoreRules();
        solver.saveMavenCleanIgnoreRules();
        solver.saveMavenPublishedRules();
        solver.saveSubstvars();

        if (!solver.getIssues().isEmpty()) {
            System.exit(1);
        }
    }

    private static int inc(int i, String[] args) {
        do {
            i++;
        } while (i < args.length && args[i].isEmpty());
        return i;
    }
}
