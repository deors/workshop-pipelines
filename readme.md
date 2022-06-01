# workshop-pipelines

Workshop about CI/CD pipelines with Jenkins and Docker.

Workshop delivered in UMA Hackers Week 6 and in Opensouthcode 2019.

## Preparing for the workshop

Docker is the only pre-requisite. This workshop works with Docker native in any Linux box, with Docker for Mac, and with Docker for Windows.

### Launching Jenkins and SonarQube

Both Jenkins and SonarQube servers are required for running the pipelines and code inspection. Although there are many ways to have Jenkins and SonarQube up and running, this is probably the easiest, fastest one -- running them as Docker containers:

    docker network create ci

    docker run --name ci-jenkins \
        --user root \
        --detach \
        --network ci \
        --publish 9080:8080 --publish 50000:50000 \
        --mount type=volume,source=ci-jenkins-home,target=/var/jenkins_home \
        --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
        --mount type=bind,source=/usr/local/bin/docker,target=/usr/local/bin/docker \
        --env JAVA_OPTS="-Xmx2048M" \
        --env JENKINS_OPTS="--prefix=/jenkins" \
        jenkins/jenkins:2.350

    docker run --name ci-sonarqube-data \
        --detach \
        --network ci \
        --mount type=volume,source=ci-postgresql-home,target=/var/lib/postgresql \
        --mount type=volume,source=ci-sonarqube-data,target=/var/lib/postgresql/data \
        --env POSTGRES_USER="sonar" \
        --env POSTGRES_PASSWORD="sonarsonar" \
        postgres:13.7

    sleep 10

    docker run --name ci-sonarqube \
        --detach \
        --network ci \
        --publish 9000:9000 \
        --mount type=volume,source=ci-sonarqube-extensions,target=/opt/sonarqube/extensions \
        --mount type=volume,source=ci-sonarqube-esdata,target=/opt/sonarqube/data \
        --env SONARQUBE_JDBC_URL="jdbc:postgresql://ci-sonarqube-data:5432/sonar?charSet=UNICODE" \
        --env SONARQUBE_JDBC_USERNAME="sonar" \
        --env SONARQUBE_JDBC_PASSWORD="sonarsonar" \
        sonarqube:9.4-community -Dsonar.web.context=/sonarqube

Note that the preceding commands will set up persistent volumes so all configuration, plugins and data persists across server restarts.

Depending on the underlying OS, Docker daemon might be in a different folder. In those cases, use the right path e.g. `/usr/bin/docker` (the `where` command might be of help).

### Jenkins configuration

On first run, Jenkins will show a wizard to configure the instance. This configuration needs to be done only on first run.

The first step is to confirm the initial administrator password which is kept safe in the `ci-jenkins-home` volume. Simply navigate to the right folder inside the volume, and take note of the initial password.

Next step is to install an initial selection of plugins. Starting with the suggested plugins is generally a good idea.

To complete the wizard, create the first administrator user. Take note of the user and password as it will be required to login into Jenkins from now on.

Once the wizard finishes the initial configuration, there are few other plugins that will be used in the workshop. To install them, click on `Manage Jenkins` menu option and next click on `Manage Plugins` menu option. In the Available tab, search for the required plugins, click the selection checkbox and then, at the bottom of the page, select the action `Install without restart`. The plugins needed are:

- `Docker Pipeline`
- `Pipeline Utility Steps`
- `JaCoCo`
- `OWASP Dependency-Check`
- `Performance`
- `SonarQube Scanner`

### SonarQube configuration

To integrate SonarQube with Jenkins, the Jenkins plugin must be configured to reach out to the right SonarQube instance when required.

Before configuring that integration, a SonarQube API token must be created. That token is required to authenticate requests coming from Jenkins.

Login to SonarQube using the default credentials: both username and password are simply `admin`. On first run, a tutorial wizard will show but it can be skipped for now.

Click on `Administration` on the top menu and afterwards on `Security` and `Users` in the horizonal menu below. In the `Administrator` user configuration row, there is a menu icon to the right with the label `Update Tokens`. Click on it, and in the pop-up dialog, in the text box below `Generate Tokens` enter `ci-sonarqube` (or any other meaningful name) and press the `Generate` button. The API token will be shown below. Take note of it, as this is the last time it will be shown in the UI.

Before leaving SonarQube, let's configure the webhook that will be leveraged by SonarQube to let Jenkins know that a requested analysis has finished.

Click on `Administration` on the top menu and afterwards on `Configuration`and `Webhooks` in the horizontal menu below. Click the `Create` button. Enter `ci-jenkins` for the webhook name, and for the URL, the Jenkins home URL appending `/sonarqube-webhook`. For example, for a server running on AWS EC2, the URL would look like: `http://ec2-xxx-xxx-xxx-xxx.eu-west-1.compute.amazonaws.com:9080/jenkins/sonarqube-webhook`. Click the `Create` button and configuration on the SonarQube side is ready.

Login to Jenkins with the previously configured administrator credentials.

Click on `Manage Jenkins` menu option and next click on `Manage Credentials` menu option. In the credentials store table, click on the link labeled as `(global)` for the Jenkins global domain.

Next, click on `Add Credentials` in the left menu. In the credential kind select `Secret text`. The secret value is the API token just generated. The secret id can be `ci-sonarqube` as well. Press `Create` when finished to save the credentials in the store.

Next, let's configure the SonarQube server integration. Go back to the dashboard, click on `Manage Jenkins` menu option and next click on `Configure System` menu option. Scroll down until the section `SonarQube Servers` is visible. Click the checkbox to allow injection of server configuration.

Next, let's add the SonarQube instance name and URL. To ensure that the right server is used by the pipeline use `ci-sonarqube` for the instance name. If the selected name is different, it should match the name referenced in the pipeline later. For the server URL, use the SonarQube home URL. For example, for a server running on AWS EC2, the URL would look like: `http://ec2-xxx-xxx-xxx-xxx.eu-west-1.compute.amazonaws.com:9000/sonarqube`. Finally, for the server authentication token, use the API token stored in the `ci-sonarqube` credential created before. Click the `Save` button and configuration on the Jenkins side is ready.

### Configuring credentials for Docker Hub

At a later point during the pipeline execution, validated Docker images are going to be published into Docker Hub. For that to be possible, Docker Hub credential must be configured before using Jenkins credentials manager.

Click on `Manage Jenkins` menu option and next click on `Manage Credentials` menu option, and then click on the link labeled as `(global)` for the Jenkins global domain. This is the same domain where the SonarQube API token was configured before.

Next, click on `Add Credentials` in the left menu and enter the credentials needed to access Docker Hub.

In the `ID` field, enter the credential id as it is going to be referenced from the pipeline, e.g. use `myorgname-docker-hub` where `myorgname` is the organization name in Docker Hub. Press `Create` when finished to save the credentials in the store.

## The anatomy of a Jenkins pipeline

A Jenkins pipeline, written in the form of a declarative pipeline with a rich DSL and semantics, the *Jenkinsfile*, is a model for any process, understood as a succession of stages and steps, sequential, parallel or any combination of both. In this context, the process is a build process, following the principles of continuous integration, continuous code inspection and continuous testing (continuous integration pipeline, for short).

Jenkins pipelines are written in Groovy, and the pipeline DSL is designed to be pluggable, so any given plugin may contribute with its own idioms to the pipeline DSL, as well as extended through custom functions bundled in Jenkins libraries.

The combination of a powerful dynamic language as Groovy, with the rich semantics of the available DSLs, allows developers to write simple, expressive pipelines, while having all freedom to customize the pipeline behavior up to the smallest detail.

The pipeline main construct is the `pipeline` block element. Inside any `pipeline` element there will be any number of second-level constructs, being the main ones:

- `agent`: Used to define how the pipeline will be executed. For example, in a specific slave or in a container created from an existing Docker image.
- `environment`: Used to define pipeline properties. For example, define a container name from the given build number, or define a credential password by reading its value from Jenkins credentials manager.
- `stages`: The main block, where all stages and steps are defined.
- `post`: Used to define any post-process activities, like resource cleaning or results publishing.

Inside the `stages` element, there will be nested at least one `stage` element, each stage with a given name. Inside each `stage` element, typically there will be one `steps` element, although other elements can be there too, for example when stage-specific configuration is needed, or to model parallel execution of certain steps.

In a nutshell, the following is a skeleton of a typical Jenkins pipeline:

```groovy
#!groovy

pipeline {
    agent {
        // how the pipeline will be built
    }

    environment {
        // properties or environment variables, new or derived
    }

    stages {
        stage('stage-1-name') {
            steps {
                // steps for stage 1 come here
            }
        }

        ...

        stage('stage-n-name') {
            steps {
                // steps for stage n come here
            }
        }
    }

    post {
        // post-process activities, e.g. cleanup or publish
    }
}
```

Typical steps include the following:

- `echo`: Step to... echo stuff to the console.
- `sh`: Used to execute any command. Probably the most common one.
- `junit`: Used to publish results of unit test execution with JUnit.
- `archiveArtifacts`: Used to archive any artifact produced during the build.
- `script`: As the name suggest, it is used to contain any arbitrary block of Groovy code.

With the building blocks just explained, as well as others, it is possible to model any continuous integration process.

## Verification activities along the pipeline

An effective continuous integration pipeline must have sufficient verification steps as to give confidence in the process. Verification steps will include code inspection, and testing:

Code inspection activities are basically three:

- **Gathering of metrics**: Size, complexity, code duplications, and others related with architecture and design implementation.
- **Static code profiling**: Analysis of sources looking for known patterns that may result in vulnerabilities, reliability issues, performance issues, or affect maintainability.
- **Dependency analysis**: Analysis of dependency manifests (e.g. those included in `pom.xml` or `require.js` files), looking for known vulnerabilities in those dependencies, as published in well-known databases like CVE.

Testing activities will include the following:

- **Unit tests**.
- **Unit-integration tests**: Those that, although not requiring the application to be deployed, are testing multiple components together. For example, in-container tests.
- **Integration tests**: Including in this group all those kinds of tests that require the application to be deployed. Typically external dependencies will be mocked up in this step. Integration tests will include API tests and UI tests.
- **Performance tests**: Tests verifying how the service or component behaves under load. Performance tests in this step are not meant to assess the overall system capacity (which can be virtually infinite with the appropriate scaling patterns), but to assess the capacity of one node, uncover concurrence issues due to the parallel execution of tasks, as well as to pinpoint possible bottlenecks or resource leaks when studying the trend. Very useful at this step to leverage APM tools to gather internal JVM metrics, e.g. to analyze gargabe collection.
- **Security tests**: Tests assessing possible vulnerabilities exposed by the application. In this step, the kind of security tests performed are typically DAST analysis.

In addition to the previous kinds of tests, there is one more which is meant to assess the quality of tests:

- **Mutation tests**: Mutation testing, usually executed only on unit tests for the sake of execution time, is a technique that identifies changes in source code, the so called mutations, applies them and re-execute the corresponding unit tests. If after a change in the source code, unit tests do not fail, that means that either the test code does not have assertions, or there are assertions but test coverage is unsufficient (typically test cases with certain conditions not tested). Mutation testing will uncover untested test cases, test cases without assertions and test cases with insufficient or wrong assertions.

To enable these tools along the lifecycle, and to align developer workstation usage with CI server pipeline usage, the recommended approach is to configure these activities with the appropriate tools in Maven's `pom.xml`, storing the corresponding test scripts, data and configuration, along with the source code in the `src/test` folder (very commonly done for unit tests, and also recommended for the other kinds of tests).

## Explaining stuff in Maven's pom.xml

### Adding JaCoCo agent to gather code coverage metrics during tests

One of the actions to be done along the pipeline, is to gather code coverage metrics when unit tests and integration tests are executed. To do that, there are a few actions needed in preparation for the task.

First, the JaCoCo agent must be added as a Maven dependency in `pom.xml`:

```xml
    <dependencies>
        ...
        <dependency>
            <groupId>org.jacoco</groupId>
            <artifactId>org.jacoco.agent</artifactId>
            <version>0.8.8</version>
            <classifier>runtime</classifier>
            <scope>test</scope>
        </dependency>
        ...
    </dependencies>
```

To enable the gathering of code coverage metrics during unit tests, the agent provides a goal to prepare the needed JVM argument. Another possible approach, to ensure that the agent is always enabled, is to pass the JVM argument directly to Surefire plugin:

```xml
    <build>
        ...
        <plugins>
            ...
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <argLine>-javaagent:${settings.localRepository}/org/jacoco/org.jacoco.agent/0.8.8/org.jacoco.agent-0.8.8-runtime.jar=destfile=${project.build.directory}/jacoco.exec</argLine>
                    <excludes>
                        <exclude>**/*IntegrationTest.java</exclude>
                    </excludes>
                </configuration>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

For integration tests, the code coverage setup is a bit more complicated. Instead of enabling the agent in the test executor, it is the test server the process that must have the agent enabled. The former approach works for unit tests because the same JVM process holds both the test code and the code for the application being tested. However for integration tests, the test execution is a separate process from the application being tested.

As the application is packaged and runs as a Docker image, the agent file must be present at the image build time. Later, during the execution of integration tests, the JaCoCo CLI tool will be needed to dump the coverage data from the test server. To do that, both dependencies will be copied into the expected folder with the help of the Maven Dependency plugin:

```xml
    <build>
        ...
        <plugins>
            ...
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <version>3.1.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>copy</goal>
                        </goals>
                        <configuration>
                            <artifactItems>
                                <artifactItem>
                                    <groupId>org.jacoco</groupId>
                                    <artifactId>org.jacoco.agent</artifactId>
                                    <version>0.8.8</version>
                                    <classifier>runtime</classifier>
                                    <destFileName>jacocoagent.jar</destFileName>
                                </artifactItem>
                                <artifactItem>
                                    <groupId>org.jacoco</groupId>
                                    <artifactId>org.jacoco.cli</artifactId>
                                    <version>0.8.8</version>
                                    <classifier>nodeps</classifier>
                                    <destFileName>jacococli.jar</destFileName>
                                </artifactItem>
                            </artifactItems>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

And finally, the JaCoCo agent needs to be copied into the Docker image. Edit the file `Dockerfile` and add a new `ADD` instruction after `VOLUME`:

```dockerfile
    ...
    VOLUME /tmp
    ADD target/dependency/jacocoagent.jar jacocoagent.jar
    ...
```

### Configuring Failsafe for integration test execution

Although Maven Surefire plugin is enabled by default, Failsafe, the Surefire twin for integration tests, is disabled by default. To enable Failsafe, its targets must be called explicitely or alternatively may be binded to the corresponding lifecycle goals. To better control its execution in the pipeline it is preferred to disable Failsafe by default:

```xml
    <build>
        ...
        <plugins>
            ...
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <includes>
                        <include>**/*IntegrationTest.java</include>
                    </includes>
                </configuration>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

In addition to the optional automatic activation of Failsafe, the configuration includes the execution filter: the pattern to recognize which test classes are integration tests vs. unit tests.

### Adding performance tests with Apache JMeter

The next addition to the project configuration is the addition of performance tests with Apache JMeter.

Besides the addition of the plugin, and optionally enabling the automatic execution of the plugin targets, the configuration will include three properties that will be injected into the scripts. Those properties - host, port, context root - are needed so the script can be executed regardless of where the application being tested is exposed, which is usually only known at runtime when the container is run:

```xml
    <build>
        ...
        <plugins>
            ...
            <plugin>
                <groupId>com.lazerycode.jmeter</groupId>
                <artifactId>jmeter-maven-plugin</artifactId>
                <version>3.5.0</version>
                <configuration>
                    <testResultsTimestamp>false</testResultsTimestamp>
                    <propertiesUser>
                        <host>${jmeter.target.host}</host>
                        <port>${jmeter.target.port}</port>
                        <root>${jmeter.target.root}</root>
                    </propertiesUser>
                </configuration>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

### Configuring mutation testing

The next step is to configure mutation testing with Pitest.

As mutation testing works better with strict unit tests, the plugin configuration should exclude application (in-container) tests and integration tests. If left enabled, mutation testing is likely to take a very long time to finish, and results obtained are likely to not be useful at all.

It is also needed to enable JUnit 5 support in Pitest explicitly by adding the corresponding dependency.

```xml
    <build>
        ...
        <plugins>
            ...
            <plugin>
                <groupId>org.pitest</groupId>
                <artifactId>pitest-maven</artifactId>
                <version>1.5.2</version>
                <configuration>
                    <excludedTestClasses>
                        <param>*ApplicationTests</param>
                        <param>*IntegrationTest</param>
                    </excludedTestClasses>
                    <outputFormats>
                        <outputFormat>XML</outputFormat>
                    </outputFormats>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-junit5-plugin</artifactId>
                        <version>0.11</version>
                    </dependency>
                </dependencies>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

Due to how Pitest plugin works, it will fail when there are no mutable tests i.e. no strict unit tests. Considering this, the pipelines corresponding to services without mutable tests should skip the execution of Pitest.

### Configuring dependency vulnerability scans with OWASP

OWASP is a global organization focused on secure development practices. OWASP also owns several open source tools, including OWASP Dependency Check. Dependency Check scans dependencies from a project manifest, like the `pom.xml` file, and checks them with the online repository of known vulnerabilities (CVE, maintained by NIST), for every framework artefact, and version.

Adding support for Dependency Check scans is as simple as adding the corresponding Maven plug-in to `pom.xml`:

```xml
    <build>
        ...
        <plugins>
            ...
            <plugin>
                <groupId>org.owasp</groupId>
                <artifactId>dependency-check-maven</artifactId>
                <version>5.3.2</version>
                <configuration>
                    <format>ALL</format>
                </configuration>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

## Orchestrating the build - the continuous integration pipeline

The stages that are proposed as a best practice, are the following:

- **Environment preparation**: This stage is used to get and configure all needed dependencies. Typically in a Java with Maven or Gradle pipeline, it is skipped as Maven and Gradle handle dependency resolution and acquisition (download from central, project/organization repository, local cache) as needed. In a Python with pip pipeline, this stage will mean the execution of `pip install` command, and similarly in a JavaScript with npm pipeline, `npm install` command.
- **Compilation**: This stage is used to transform source code to binaries, or in general to transform source code into the final executable form, including transpilation, uglyfication or minification. For interpreted languages, whenever possible this stage should also include checking for syntax errors.
- **Unit tests**: This stage is used to execute unit tests (understood as tests which do not need the application to be installed or deployed, like unit-integration tests). Along with test execution, the stage should also gather code coverage metrics.
- **Mutation tests**: This stage is used to run mutation testing to measure how thorough (and hence useful) automated unit tests are.
- **Package**: This stage is used to package all application resources that are required at runtime, for example: binaries, static resources, and application configuration that does not depend on the environment. As a best practice, all environment specific configuration must be externalized (package-one-run-everywhere).
- **Build Docker image**: This stage will create the application image by putting together all pieces required: a base image, the build artifacts that were packaged in the previous stage, and any dependencies needed (e.g. third-party libraries).
- **Run Docker image**: This stage will prepare the test environment for the following stages. This stage tests whether the application actually runs and then makes it available for the tests.
- **Integration tests**: This stage will execute integration tests on the test environment just provisioned. As with unit tests, code coverage metrics should be gathered.
- **Performance tests**: This stage will run tests to validate the application behavior under load. This kind of tests, although provides with useful information on response times, are better used to uncover any issue due to concurrent usage.
- **Dependency vulnerability tests**: This stage is used to assess the application vulnerabilities and determine whether there are known security vulnerabilities which should prevent the application to be deployed any further.
- **Code inspection**: This stage is used to run static code analysis and gather useful metrics (like object-oriented metrics). Typically this stage will also include observations from previous stages to calculate the final quality gate for the build.
- **Push Docker image**: The final stage, if all quality gates are passed, is to push the image to a shared registry, from where it is available during tests to other applications that depend on this image, as well as to be promoted to stage or production environments.

Once the pipeline to be created is known, it is the time of putting together all the pieces and commands needed to execute every activity.

### The pipeline code 1: Configuring the build execution environment

First, the pipeline must declare the agent to be used for the build execution, and the build properties that will be leveraged later during stage definition, to make stages reusable for every microservice in the system. In this case, the JDK 18 standard image from Eclipse Temurin project is used to run the build:

```groovy
#!groovy

pipeline {
    agent {
        docker {
            image 'eclipse-temurin:17.0.3_7-jdk'
            args '--network ci'
        }
    }

    environment {
        ORG_NAME = 'deors'
        APP_NAME = 'workshop-pipelines'
        APP_CONTEXT_ROOT = '/'
        APP_LISTENING_PORT = '8080'
        TEST_CONTAINER_NAME = "ci-${APP_NAME}-${BUILD_NUMBER}"
        DOCKER_HUB = credentials("${ORG_NAME}-docker-hub")
    }
    ...
}
```

The network used to create the builder container should be the same where the test container is launched, to ensure that integration and performance tests, that are executed from the builder container, have connectivity with the application under test. That network must exist in the Docker machine or Docker Swarm cluster before the builds are launched.

The property `DOCKER_HUB` will hold the value of the credentials needed to push images to Docker Hub (or to any other Docker registry). The credentials are stored in Jenkins credential manager, and injected into the pipeline with the `credentials` function. This is a very elegant and clean way to inject credentials as well as any other secret, without hard-coding them (and catastrophically storing them in version control).

As the build is currently configured, it will run completely clean every time, including the acquisition of dependencies by Maven. In those cases in which it is not advisable to download all dependencies in every build, for example because build execution time is more critical than ensuring that dependencies remain accessible, builds can be accelerated by caching dependencies (the local Maven repository) in a volume:

```groovy
    ...
    agent {
        docker {
            image 'eclipse-temurin:17.0.3_7-jdk'
            args '--network ci --mount type=volume,source=ci-maven-home,target=/root/.m2'
        }
    }
    ...
```

### The pipeline code 2: Compilation, unit tests, mutation tests and packaging

The first four stages will take care of compilation, unit tests, muration tests and packaging tasks:

```groovy
    ...
    stages {
        stage('Compile') {
            steps {
                echo '-=- compiling project -=-'
                sh './mvnw clean compile'
            }
        }

        stage('Unit tests') {
            steps {
                echo '-=- execute unit tests -=-'
                sh './mvnw test org.jacoco:jacoco-maven-plugin:report'
                junit 'target/surefire-reports/*.xml'
                jacoco execPattern: 'target/jacoco.exec'
            }
        }

        stage('Mutation tests') {
            steps {
                echo '-=- execute mutation tests -=-'
                sh './mvnw org.pitest:pitest-maven:mutationCoverage'
            }
        }

        stage('Package') {
            steps {
                echo '-=- packaging project -=-'
                sh './mvnw package -DskipTests'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }
        ...
    }
    ...
```

It's worth noting that as JaCoCo agent is already configured in `pom.xml` file, running the `test` goal in Maven will also gather the code coverage metrics.

### The pipeline code 3: Build the Docker image and provision the test environment

The next two stages will build the Docker image and provision the test environment by running it:

```groovy
    ...
    stages {
        ...
        stage('Build Docker image') {
            steps {
                echo '-=- build Docker image -=-'
                sh './mvnw docker:build'
            }
        }

        stage('Run Docker image') {
            steps {
                echo '-=- run Docker image -=-'
                sh "docker run --name ${TEST_CONTAINER_NAME} --detach --rm --network ci --expose ${APP_LISTENING_PORT} --expose 6300 --env JAVA_OPTS='-Dserver.port=${APP_LISTENING_PORT} -Dspring.profiles.active=ci -javaagent:/jacocoagent.jar=output=tcpserver,address=*,port=6300' ${ORG_NAME}/${APP_NAME}:latest"
            }
        }
        ...
    }
    ...
```

When the test environment is created, there are some important notes to take into consideration.

The network used to run the container, as explained before, should be the same in which the build is running. This way, there is network visibility and the network DNS can be use to resolve easily where the test container is running.

The test container name is set to include the build number, which allows for parallel execution of builds, i.e. when rapid feedback is required for every commit.

The port in which the application listens is configured with a property which is used consistently to ensure that connectivity works fine, i.e. the server starts in a known port, that port is exposed (but not needed to be published outside the network), and later, the test executors point to that very same port.

The application runs with a given Spring profile activated. This is used to inject test environment specific configuration properties, for example settings that would be pulled from configservice which is not available during this test phase.

The application runs with JaCoCo agent activated and listening in port 6300. Later during integration tests, the build will connect to that port to dump code coverage information from the server.

### The pipeline code 4: Running integration and performance tests

The following two steps will execute the integration and performance tests, once the application is deployed and available in the test environment:

```groovy
    ...
    stages {
        ...
        stage('Integration tests') {
            steps {
                echo '-=- execute integration tests -=-'
                sh "curl --retry 5 --retry-connrefused --connect-timeout 5 --max-time 5 http://${TEST_CONTAINER_NAME}:${APP_LISTENING_PORT}/${APP_CONTEXT_ROOT}/actuator/health"
                sh "./mvnw failsafe:integration-test failsafe:verify -DargLine=\"-Dtest.target.server.url=http://${TEST_CONTAINER_NAME}:${APP_LISTENING_PORT}/${APP_CONTEXT_ROOT}\""
                sh "java -jar target/dependency/jacococli.jar dump --address ${TEST_CONTAINER_NAME} --port 6300 --destfile target/jacoco-it.exec"
                sh 'mkdir target/site/jacoco-it'
                sh 'java -jar target/dependency/jacococli.jar report target/jacoco-it.exec --classfiles target/classes --xml target/site/jacoco-it/jacoco.xml'
                junit 'target/failsafe-reports/*.xml'
                jacoco execPattern: 'target/jacoco-it.exec'
            }
        }

        stage('Performance tests') {
            steps {
                echo '-=- execute performance tests -=-'
                sh "./mvnw jmeter:configure@configuration jmeter:jmeter jmeter:results -Djmeter.target.host=${TEST_CONTAINER_NAME} -Djmeter.target.port=${APP_LISTENING_PORT} -Djmeter.target.root=${APP_CONTEXT_ROOT}"
                perfReport sourceDataFiles: 'target/jmeter/results/*.csv'
            }
        }
        ...
    }
    ...
```

There a few outstanding pieces that are worth noting.

Before the integration tests are launched, it is good idea to ensure that the application is fully initialised and responding. The Docker run command will return once the container is up, but this does not mean that the application is up and running and able to respond to requests. With a simple `curl` command it is possible to configure the pipeline to wait for the application to be available.

Integration and performance tests are executing by passing as a parameter the root URL where the application is to be found, including the test container name that will be resolved thanks to the network DNS, and the configured port.

Code coverage information is being gathered in the test container. Therefore, to have it available for the quality gate and report publishing, the JaCoCo CLI `dump` command is executed. The JaCoCo CLI is available in the `target/dependency` folder as it was configured before with the help of the Maven dependency plugin.

For performance tests, it is possible to include a quality gate in the `perfReport` function, causing the build to fail if any of the thresholds are not passed, as well as flagging a build as unstable. As an example, this is a quality gate flagging the build as unstable in case of at least one failed request or if average response time exceeds 100 ms, and failing the build if there are 5% or more of failing requests.

```groovy
        ...
        stage('Performance tests') {
            steps {
                echo '-=- execute performance tests -=-'
                sh "./mvnw jmeter:configure@configuration jmeter:jmeter jmeter:results -Djmeter.target.host=${TEST_CONTAINER_NAME} -Djmeter.target.port=${APP_LISTENING_PORT} -Djmeter.target.root=${APP_CONTEXT_ROOT}"
                perfReport sourceDataFiles: 'target/jmeter/results/*.csv', errorUnstableThreshold: 0, errorFailedThreshold: 5, errorUnstableResponseTimeThreshold: 'default.jtl:100'
            }
        }
        ...
```

### The pipeline code 5: Dependency vulnerability scan, code inspection and quality gate

The next two stages will check dependencies for known security vulnerabilities, and execute code inspection with SonarQube (and tools enabled through plugins), including any compound quality gate defined in SonarQube for the technology or project:

```groovy
    ...
    stages {
        ...
        stage('Dependency vulnerability scan') {
            steps {
                echo '-=- run dependency vulnerability scan -=-'
                sh './mvnw dependency-check:check'
                dependencyCheckPublisher
            }
        }

        stage('Code inspection & quality gate') {
            steps {
                echo '-=- run code inspection & check quality gate -=-'
                withSonarQubeEnv('ci-sonarqube') {
                    sh './mvnw sonar:sonar'
                }
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }
        ...
    }
    ...
```

For dependency check, it is possible to include a quality gate in the `dependencyCheckPublisher` function, causing the build to fail if any of the thresholds are not passed, as well as flagging a build as unstable.

As of the time of this update, Spring Boot 2.7.0 has 7 known vulnerabilities without a patch. The quality gate shown below will accept those vulnerabilities (3 critical, 4 medium severity) and will fail in case additional vulnerabilities are detected.

However, the publisher function will not break the build immediately when found vulnerabilities exceed the configured threshold. To close that gap, a simple script can be added to the scan step:

```groovy
        ...
        stage('Dependency vulnerability scan') {
            steps {
                echo '-=- run dependency vulnerability scan -=-'
                sh './mvnw dependency-check:check'
                dependencyCheckPublisher failedTotalCritical: '4', unstableTotalCritical: '4', failedTotalHigh: '0', unstableTotalHigh: '0', failedTotalMedium: '5', unstableTotalMedium: '5'
                script {
                    if (currentBuild.result == 'FAILURE') {
                        error('Dependency vulnerabilities exceed the configured threshold')
                    }
                }
            }
        }
        ...
```

It's worth noting that the code analysis and calculation of the quality gate by SonarQube is an asynchronous process. Depending on SonarQube server load, it might take some time for results to be available, and as a design decision, the `sonar:sonar` goal will not wait, blocking the build, until then. This has the beneficial side effect that the Jenkins executor is not blocked and other builds might be built in the meantime, maximizing the utilization of Jenkins build farm resources.

The default behavior for SonarQube quality gate, as coded in the `waitForQualityGate` function, is to break the build in case any of the thresholds defined in the gate is not achieved.

### The pipeline code 6: Pushing the Docker image

At this point of the pipeline, if all quality gates have passed, the produced image can be considered as a stable, releasable version, and hence might be published to a shared Docker registry, like Docker Hub, as the final stage of the pipeline:

```groovy
    ...
    stages {
        ...
        stage('Push Docker image') {
            steps {
                echo '-=- push Docker image -=-'
                sh './mvnw docker:push'
            }
        }
    }
    ...
```

For this command to work, the right credentials must be set. The Spotify Docker plugin that is being used, configures registry credentials in various ways but particularly one is perfect for pipeline usage, as it does not require any pre-configuration in the builder container: credential injection through Maven settings.

So far, the following configuration pieces are set: Spotify plugin sets the server id with the following configuration setting `<serverId>docker-hub</serverId>`, and the pipeline gets the credentials injected as the `DOCKER_HUB` property.

Actually, unseen by the eye, other two properties are created by Jenkins `credentials` function: `DOCKER_HUB_USR` and `DOCKER_HUB_PSW`.

To finally connect all the dots together, it is needed that Maven processes, specifically this one, has special settings to inject the credential so it's available to Spotify plugin.

The easiest way to do that, is to create a `.mvn/maven.config` file with the reference to the settings file to be used, and the Maven wrapper, the `mvnw` command that is being used along the pipeline, will pick those settings automatically.

These are the contents for the `.mvn/maven.config` file:

    -s .mvn/settings.xml

And these are the contents for the `.mvn/settings.xml` file which is being referenced:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">

    <interactiveMode>true</interactiveMode>
    <offline>false</offline>
    <pluginGroups/>
    <proxies/>
    <servers>
        <server>
            <id>docker-hub</id>
            <username>${env.DOCKER_HUB_USR}</username>
            <password>${env.DOCKER_HUB_PSW}</password>
        </server>
    </servers>
    <mirrors/>
    <profiles/>

</settings>
```

### The pipeline code 7: Cleaning up resources

The final piece to set is the `post` block to clean up any resources. In this case, the test container should be removed:

```groovy
#!groovy

pipeline {
    ...
    post {
        always {
            echo '-=- remove deployment -=-'
            sh "docker stop ${TEST_CONTAINER_NAME}"
        }
    }
}
```

## Running the pipeline

Once all the pieces are together, pipelines configured for every service in our system, it's the time to add the job to Jenkins and execute it.

Green balls!
