# workshop-pipelines

Workshop on Jenkins CI/CD pipelines.

Workshop delivered in UMA Hackers Week 6 and in OpenSouthCode 2019 leveraging Docker as the container runtime.

Major workshop refresh delivered first in OpenSlava 2023 leveraging Kubernetes via Rancher Desktop and K3s.

## Part 1. Preparing for the workshop

Rancher Desktop is the only pre-requisite. This workshop has been tested to work with Rancher Desktop 1.10 on macOS Ventura 13.6. It should work on any other Rancher Desktop environment, K3s, or even vanilla Kubernetes.

If using a managed Kubernetes cluster, it is likely that there some important services, such as authentication or networking, coupled to the specific platform. If that is your case, you may continue to leverage large portions of the workshop, but the necessary adaptations will not be covered here.

### 1.1. Configure K3s

Rancher Desktop comes with K3s, a lightweight Kubernetes distribution optimized to be used in a workstation and other resource-limited environments.
Although normally K3s will work just fine out of the box, there are a few tweaks that we need to perform before starting with the workshop itself.

First, let's create a local folder in your workstation that will be used to create persistent volumes for Jenkins and other tools. In that way, the data will survive crashes or cluster restarts. I recommend to create the folder on your home holder:

    mkdir ~/data

Next, we must override Lima settings to increase the max virtual memory areas value and to mount the folder created above. To override those settings, simply create a file in Lima config folder `~/Library/Application Support/rancher-desktop/lima/_config/override.yaml` and include the following content:

    provision:
    - mode: system
    script: |
        #!/bin/sh
        sysctl -w vm.max_map_count=262144
    mounts:
    - location: /Users/<<username>>/data
    mountPoint: /data
    writable: true

Alternatively, use the `src/etc/k3s-lima-override.yaml` file available in the repository. After the file is created, reset K3s for the changes to be applied.

To validate whether the changes are effectively applied, let's "jump" into the Lima VM by running this command:

    rdctl shell

And once inside, check for the expected changes:

    sysctl vm.max_map_count # should return 262144
    ls /data # should confirm that the directory is mounted

The third step is to prepare the Kubeconfig file. When Rancher Desktop is installed, the cluster configuration is stored in `~/.kube/config`. If, for some reason, you have lost the file, it can be retrieved from the Lima VM by running this command:

    rdctl shell sudo cat /etc/rancher/k3s/k3s.yaml

That configuration file is not ready yet for our needs. If you check the settings, the server IP address is set to `127.0.0.1` and when later we use those settings to connect with the cluster API, it would not work. Therefore, the configuration must be updated to use the cluster IP address. To get that IP run this command:

    kubectl get services

The only service available at this point should be precisely the Kubernetes service, so take note of that IP and use it to fix the Kubeconfig file. Save that file in a safe place as it will be needed in a few minutes to configure Jenkins integration with the cluster.

### 1.2. Run Jenkins

To run Jenkins, let's use the official image, adding a persistent volume and routing through Traefik (which is available in Rancher Desktop out of the box). An exemplar YAML file with the needed configuration is available in `src/etc` folder:

    kubectl apply -f src/etc/ci-jenkins.yaml

To verify that the whole deployment was right, use the following command:

    kubectl get all,ingress,pv,pvc

You should see that a pod, service, deployment, replica set, ingress, persistent volume, and persistent volume claim object are created in the cluster.

Take note of the IP address for the Jenkins master service as it will be needed later to configure the integration between Jenkins and the cluster. In my own tests, there are situations in which K3s networking does not work as intended (e.g., moving the workstation from home wifi to office wifi). As a workaround, it is possible to use the Jenkins master pod IP instead of the service IP. To easily get the pod IP you may run this command (use the pod id listed by previous `kubectl get` command):

    kubectl describe pod/ci-jenkins-<<rest-of-pod-id>> | grep IP:

### 1.3. Configure Jenkins

Thanks to Traefik, we can access the Jenkins UI through `http://localhost/jenkins`. Alternatively, it is also possible to configure port forwarding in Rancher Desktop.

On first run, Jenkins will show a wizard to configure the instance. This configuration needs to be done only on first run (unless the persistent volume is not configured properly as explained above).

The first step is to confirm the initial administrator password which is kept safe in the `jenkins-home` volume. You may get that password with any of these commands (remember that we mapped the volume to a folder in our workstation):

    cat ~/data/ci-jenkins-home/secrets/initialAdminPassword
    rdctl shell cat /data/ci-jenkins-home/secrets/initialAdminPassword

Next step is to install the initial set of plugins. Starting with the suggested plugins is generally a good idea.

To complete the wizard, create the first administrator user. Take note of the user and password as it will be required to login into Jenkins from now on. I strongly recommend to not use typical passwords as `admin`, `adminadmin`, or `12345` as some King Roland would do.

Once the Jenkins setup wizard finishes the initial configuration, there are few other plugins that will be used in the pipeline. To install them, click on the `Manage Jenkins` left menu option and next click on the `Plugins` center menu option (under `System Configuration` section). Click on the `Available plugins` left menu option, search iteratively for each one of the plugins, click the selection checkbox and then when ready push the `Install` button. The required plugins are:

- `Kubernetes`
- `Pipeline Utility Steps`
- `JaCoCo`
- `OWASP Dependency-Check`
- `Performance`
- `SonarQube Scanner`

### 1.4. Configure Jenkins integration with Kubernetes

Once the plug-ins are ready, let's configure the Kubernetes cloud to launch our builds. Click on the `Manage Jenkins` left menu option and next click on the `Clouds` center menu option (under `System Configuration` section). Click the `New cloud` center menu option, use a reasonable name (e.g., `k3s-lima-vm`), select that the cloud is of `Kubernetes` type, and click the `Create` button.

Expand the `Kubernetes Cloud details` section and pay special attention to these settings are they are very sensitive to your specific networking configuration.

The Kubernetes URL must be set so it is accessible from the Jenkins pod (remember, this is why `127.0.0.1` was not an option). The cluster IP address noted before should be a good and predictable way to get the Jenkins master and the cluster API integrated from within the cluster internal network. The local workstation IP may also be an option but I've found out in my own experiments that is not reliable.

The Jenkins URL must be set so any pod scheduled to run a build is able to connect with the Jenkins master. Use the Jenkins master service/pod IP address noted before to configure this. If you have deployed Jenkins with the provided YAML file, the Jenkins master should be listening in `9090` port.

The credential needed is the Kubeconfig file prepared a few steps before. Click the `Add` button, select `Jenkins` as the credentials provider, select `Secret file` as the kind of credential, and upload the Kubeconfig from the folder where it was stored before. Choose a representative id for the credential (e.g., `k3s-lima-vm-kubeconfig`) and click the `Add` button when finished. This credential is going to be needed in the pipeline, so take note of the id for later.

Use the `Test Connection` button to check that all settings are ok. Click the `Save` button to finish the configuration.

### 1.5. Configure credentials for Docker Hub

At a later point during the pipeline execution, generated and validated Docker images are going to be published into Docker Hub. For that to be possible, the Docker Hub credential must be configured. We will use the Jenkins credentials manager for that.

Click on the `Manage Jenkins` left menu option, next click on the `Credentials` center menu option, and then click on the link labeled as `(global)` for the Jenkins global domain. This is the same domain where the Kubeconfig file was stored before.

Next, click on the `Add Credentials` button and enter the credentials needed to access Docker Hub (kind `Username with password`).

In the `ID` field, enter the credential id as it is going to be referenced from the pipeline, e.g. use `docker-hub-<<myorgname>>` where `<<myorgname>>` is the organization name in Docker Hub. Press `Create` when finished to save the credentials in the store.

### 1.6. Create and run a test job

Although in the previous step the connection between Jenkins and K3s was tested, it does not mean that a job will run. In particular, the Jenkins URL that was configured is key as it is used from the pod to attach the Jenkins agent to the master. That setting is not verified when the connection was tested, so let's create a very simple test job to verify the whole integration.

From Jenkins home click the `Create a job` center menu option. As the job name use a representative name (e.g., `test-k3s-integration`), and for the type select `Pipeline` and click the `OK` button at the bottom.

Scroll down a bit, and in the `Pipeline` section, ensure that the definition is of kind `Pipeline script` and use the following code to define the pipeline. Do not pay too much attention now to its syntax, as I will explain in a bit the anatomy of a pipeline:

```groovy
pipeline {
    agent {
        kubernetes {
            defaultContainer 'jdk'
            yaml '''
apiVersion: v1
kind: Pod
spec:
containers:
    - name: jdk
    image: docker.io/eclipse-temurin:20.0.1_9-jdk
    command:
        - cat
    tty: true
'''
        }
    }

    stages {
        stage('Check environment') {
            steps {
                sh 'java -version'
            }
        }
    }
}
```

Click the `Save` button at the bottom of the page, and on the job page select the `Build Now` left menu option.

If everything is well configured, the job console will show how the pod is scheduled, the Jenkins agent registers with the master, and the simple `java -version` command is executed demonstrating that the JDK image was downloaded and it was possible to run the tool inside the container.

## Part 2. The anatomy of a Jenkins pipeline

A Jenkins pipeline, written in the form of a declarative pipeline with a rich DSL and semantics, the *Jenkinsfile*, is a model for any process, understood as a succession of stages and steps, sequential, parallel or any combination of both. In this context, the process is a CI/CD process, providing automation for common and repetitive tasks such as building, packaging, validating, publishing or deploying a build.

### 2.1. Pipelines as code

Jenkins pipelines are written in Groovy (pipelines as code), and the pipeline DSL is designed to be pluggable, so any given plugin may contribute with its own idioms to the pipeline DSL, as well as extended through custom functions bundled in Jenkins libraries.

The combination of a powerful dynamic language as Groovy, with the rich semantics of the available DSLs, allows developers to write simple, expressive pipelines, while having all the freedom to customize the pipeline behavior up to the smallest detail.

The pipeline main construct is the `pipeline` block element. Inside any `pipeline` element there will be any number of second-level constructs, being the main ones:

- `agent`: Used to define how the pipeline will be executed. For example, in a specific agent node or in a container created from an existing Docker image.
- `environment`: Used to define pipeline properties. For example, define a container name from the given build number, or define a credential password by reading its value from Jenkins credentials manager.
- `stages`: The main block, where all stages and steps are defined.
- `post`: Used to define any post-process activities, like resource cleaning or results publishing.

Inside the `stages` element, there will be nested at least one `stage` element, each stage with a given name. Inside each `stage` element, typically there will be one `steps` element, although other elements can be there too, for example when stage-specific configuration is needed, or to model parallel execution of certain steps.

In a nutshell, the following is a skeleton of a typical Jenkins pipeline:

```groovy
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

### 2.2. Validation activities along the pipeline

An effective continuous integration pipeline must have sufficient validation steps as to give confidence in the process. Validation steps will generally be grouped in code inspection and testing tasks:

Code inspection tasks are basically three:

- **Gathering of metrics**: Size, complexity, code duplications, and others related with architecture and design implementation.
- **Static code profiling**: Analysis of source code looking for known patterns that may result in security vulnerabilities, reliability issues, performance issues, or affect maintainability.
- **Dependency analysis**: Analysis of dependency manifests (e.g. those included in `pom.xml` or `require.js` files), looking for known vulnerabilities in those dependencies, as published in well-known databases like CVE.

Testing tasks will include the following:

- **Unit tests**: Those that run at the component or function level, not requiring any dependency to be available at test time. E.g., simple tests on a class or specific methods.
- **Unit-integration tests**: Those that, although not requiring the application to be deployed, are testing multiple components together. For example, in-container tests. In terms of the technical mechanism to run these tests, they may be indistinguisable from unit tests. In practice, many times they will be executed altogether (e.g., run `mvn test` command).
- **Integration tests**: In this group are included all those tests that require the application to be deployed and with its internal dependencies integrated (including data stores such as a relational database). Typically external dependencies will be mocked up in this step (e.g., a third-pary billing system). Integration tests will include API tests and UI tests, but may require of other specialized tests (e.g., to test batch processes).
- **Performance tests**: Tests verifying how the service or component behaves under load. Performance tests in this step are not meant to assess the overall system capacity (which can be virtually infinite with the appropriate scaling patterns), but to assess the capacity of single instances, uncover concurrence issues due to the parallel execution of tasks, as well as to pinpoint possible bottlenecks or resource leaks when studying the trend. Very useful at this step to leverage APM tools to gather internal JVM metrics, e.g. to analyze gargabe collection pauses and effectiveness.
- **Security tests**: Tests assessing possible vulnerabilities exposed by the application. In this step, the kind of security tests performed are typically DAST analysis.

In addition to the previous kinds of tests, there is one more which is meant to assess the quality of tests:

- **Mutation tests**: Mutation testing, usually executed only on unit tests for the sake of total build duration, is a technique that identifies changes in source code, the so called mutations, applies them and re-execute the corresponding unit tests. If after a change in the source code the unit tests do not fail, that means that either the test code does not have assertions, or the assertions are insufficient to uncover the bug, typically because test cases with certain conditions are not implemented. Therefore, mutation testing will uncover untested test cases, test cases without assertions and test cases with insufficient or wrong assertions, which is arguably a better way to find out about the test suite quality than just gathering test code coverage metrics.

### 2.3. Enabling code inspection and testing tools in the lifecycle

To enable these tools along the lifecycle, and to align developer workstation build results with CI server build results, the recommended approach is to configure these activities with the appropriate development lifecycle tools.

For example, in the case of the Java ecosystem, a wise choice is to leverage Maven lifecycle as modeled in the `pom.xml` file, while storing the corresponding test scripts, data and configuration in the `src/test` folder (very commonly done for unit tests, and also recommended for the other kinds of tests), in the same repository as application source code.

Although it is not the purpose of this workshop to go into Maven details, I have provided at the end of this guideline an appendix about specific configurations and how they enable tool integration as described above.

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

## Adding SonarQube for code inspection and static vulnerability analysis

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

## APPENDIX. Explaining stuff in Maven's pom.xml

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
