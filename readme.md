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

To run Jenkins, let's use the official image, adding a persistent volume for Jenkins data, and routing through Traefik (which is available in Rancher Desktop out of the box). An exemplar YAML file with the needed configuration is available in `src/etc` folder:

    kubectl apply -f src/etc/ci-jenkins.yaml

To verify that the whole deployment was right, use the following command:

    kubectl get all,ingress,pv,pvc

You should see that a pod, service, deployment, replica set, ingress, persistent volume, and persistent volume claim objects are created in the cluster.

### 1.3. Configure Jenkins

Thanks to Traefik, we can access the Jenkins UI through `http://localhost/jenkins`. Alternatively, it is also possible to configure port forwarding in Rancher Desktop.

On first run, Jenkins will show a wizard to configure the instance. This configuration needs to be done only on first run (unless the persistent volume is not configured properly as explained above).

The first step is to confirm the initial administrator password which is kept safe in the `jenkins-home` volume. You may get that password with any of these commands (remember that we mapped the volume to a folder in our workstation):

    cat ~/data/ci-jenkins-home/secrets/initialAdminPassword
    rdctl shell cat /data/ci-jenkins-home/secrets/initialAdminPassword

Next step is to install the initial set of plugins. Starting with the suggested plugins is generally a good idea.

To complete the wizard, create the first administrator user. Take note of the user and password as it will be required to login into Jenkins from now on. I strongly recommend to not use typical passwords as `admin`, `adminadmin`, or `12345` as some King Roland would do.

Once the Jenkins setup wizard finishes the initial configuration, there are few other plugins that will be used in the pipeline. To install them, click on the `Manage Jenkins` left menu option and next click on the `Plugins` center menu option (under `System Configuration` section). Click on the `Available plugins` left menu option, search iteratively for each one of the plugins, click on the selection checkbox and then when ready push the `Install` button. The required plugins are:

- `Kubernetes`
- `Kubernetes CLI`
- `Pipeline Utility Steps`
- `JaCoCo`
- `OWASP Dependency-Check`
- `Performance`
- `SonarQube Scanner`

### 1.4. Configure Jenkins integration with Kubernetes

Once the plug-ins are ready, let's configure the Kubernetes cloud to launch our builds. Click on the `Manage Jenkins` left menu option and next click on the `Clouds` center menu option (under `System Configuration` section). Click on the `New cloud` center menu option, use a reasonable name (e.g., `k3s-lima-vm`), select that the cloud is of `Kubernetes` type, and click on the `Create` button.

Expand the `Kubernetes Cloud details` section and pay special attention to these settings as they are very sensitive to your specific environment:

- The Kubernetes URL is needed by the Jenkins master pod to connect with the cluster API and request to run build pods when needed. The cluster IP address noted before should be a good and predictable way to get the Jenkins master and the cluster API connected from within the cluster internal network.

- The Jenkins URL must be set so any pod scheduled to run a build is able to connect the Jenkins agent with the Jenkins master. Using the internal cluster DNS the URL will be `http://ci-jenkins:9090/jenkins`.

- The credential needed is the Kubeconfig file prepared a few steps before. Click on the `Add` button, select `Jenkins` as the credentials provider, select `Secret file` as the kind of credential, and upload the Kubeconfig from the folder where it was stored before. Choose a representative id for the credential (e.g., `k3s-lima-vm-kubeconfig`) and click on the `Add` button when finished.

Use the `Test Connection` button to check that all settings are ok. Click on the `Save` button to finish the configuration.

### 1.5. Configure credentials for Docker Hub

At a later point during the pipeline execution, generated and validated Docker images are going to be published into Docker Hub. For that to be possible, the Docker Hub credential must be configured. We will use the Jenkins credentials manager for that.

Click on the `Manage Jenkins` left menu option, next click on the `Credentials` center menu option, and then click on the link labeled as `(global)` for the Jenkins global domain. This is the same domain where the Kubeconfig file was stored before.

Next, click on the `Add Credentials` button and enter the credentials needed to access Docker Hub (kind `Username with password`).

In the `ID` field, enter the credential id as it is going to be referenced from the pipeline, e.g. use `docker-hub-<YOUR_ORG_NAME>` where `<YOUR_ORG_NAME>` is the organization name in Docker Hub. Press `Create` when finished to save the credentials in the store.

### 1.6. Create and run a test job

Although in the previous step the connection between Jenkins and K3s was tested, it does not mean that a job will run. In particular, the Jenkins URL that was configured is key as it is used from the pod to attach the Jenkins agent to the master. That setting is not verified when the connection was tested, so let's create a very simple test job to verify the whole integration.

From Jenkins home click on the `Create a job` center menu option. As the job name use a representative name (e.g., `test-k3s-integration`), and for the type select `Pipeline` and click on the `OK` button at the bottom.

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

Click on the `Save` button at the bottom of the page, and on the job page select the `Build Now` left menu option.

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

### 2.3. Integrate code inspection and testing tools in the lifecycle

To enable these tools along the lifecycle, and to align developer workstation build results with CI server build results, the recommended approach is to configure these activities with the appropriate development lifecycle tools.

For example, in the case of the Java ecosystem, a wise choice is to leverage the Apache Maven lifecycle as modeled in the `pom.xml` file, while storing the corresponding test scripts, data and configuration in the `src/test` folder (very commonly done for unit tests, and also recommended for the other kinds of tests), altogether in the same repository as application source code.

Although it is not the purpose of this workshop to go into Maven details, I have provided at the end of the workshop guideline an appendix about specific Maven configurations and how they enable tool integration as described above.

## Part 3. Build orchestration - the continuous integration pipeline

The stages that are proposed as a best practice, are the following:

- **Environment preparation**: This stage is used to get and configure all needed dependencies. Typically in a Java with Maven or Gradle pipeline, it is skipped as Maven and Gradle handle dependency resolution and acquisition (download from central, project/organization repository, local cache) as needed. In a Python with `pip` pipeline, this stage will mean the execution of `pip install` command, and similarly in a JavaScript with `npm` pipeline, `npm install` command.
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

### 3.1. The pipeline code part 1: Configure the build execution environment

First, the pipeline must declare the agent to be used for the build execution, and the build properties that will be leveraged later during each stage definition. This is a good practice to have highly standardized pipelines that can be easily reused across components of the same archetype (e.g., Java + Maven + Spring Boot).

For this pipeline, we will need three containers:

- **Eclipse Temurin JDK 20 image**: Includes the JVM runtime.
- **Podman image**: Podman will be used to build and publish container images.
- **Kubectl CLI image**: The CLI is needed to run the ephemeral test environment.

Additionally, we will configure a volume for the Maven cache (to speed up builds). Please note that both Podman and Kubectl images require elevated privileges to run.

A couple of Groovy functions are also added to improve readability of the environment variable definition section. Those functions will be placed after the pipeline block.

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
      volumeMounts:
        - name: m2-cache
          mountPath: /root/.m2
    - name: podman
      image: quay.io/containers/podman:v4.5.1
      command:
        - cat
      tty: true
      securityContext:
        runAsUser: 0
        privileged: true
    - name: kubectl
      image: docker.io/bitnami/kubectl:1.27.3
      command:
        - cat
      tty: true
      securityContext:
        runAsUser: 0
        privileged: true
  volumes:
    - name: m2-cache
      hostPath:
        path: /data/m2-cache
        type: DirectoryOrCreate
'''
        }
    }

    environment {
        APP_NAME = getPomArtifactId()
        APP_VERSION = getPomVersionNoQualifier()
        APP_CONTEXT_ROOT = '/' // it should be '/' or '<some-context>/'
        APP_LISTENING_PORT = '8080'
        APP_JACOCO_PORT = '6300'
        CONTAINER_REGISTRY_URL = 'docker.io'
        IMAGE_ORG = '<YOUR_ORG_NAME>' // change it to your own organization at Docker.io!
        IMAGE_NAME = "$IMAGE_ORG/$APP_NAME"
        IMAGE_SNAPSHOT = "$IMAGE_NAME:$APP_VERSION-snapshot-$BUILD_NUMBER" // tag for snapshot version
        IMAGE_SNAPSHOT_LATEST = "$IMAGE_NAME:latest-snapshot" // tag for latest snapshot version
        IMAGE_GA = "$IMAGE_NAME:$APP_VERSION" // tag for GA version
        IMAGE_GA_LATEST = "$IMAGE_NAME:latest" // tag for latest GA version
        EPHTEST_CONTAINER_NAME = "ephtest-$APP_NAME-snapshot-$BUILD_NUMBER"
        EPHTEST_BASE_URL = "http://$EPHTEST_CONTAINER_NAME:$APP_LISTENING_PORT".concat("/$APP_CONTEXT_ROOT".replace('//', '/'))

        // credentials
        KUBERNETES_CLUSTER_CRED_ID = 'k3s-lima-vm-kubeconfig'
        CONTAINER_REGISTRY_CRED = credentials("docker-hub-$IMAGE_ORG")
    }
    ...
}

def getPomVersion() {
    return readMavenPom().version
}

def getPomVersionNoQualifier() {
    return readMavenPom().version.split('-')[0]
}

def getPomArtifactId() {
    return readMavenPom().artifactId
}
```

As explained above, although not mandatory many of these environment variables are useful as they allows for the pipeline to be easily reusable across projects. Everything that is project-dependant is configured as a variable, and extracted from sources (e.g., the POM file) whenever possible.

As can be seen above, all secrets are injected from the Jenkins credentials manager. It is generally advisable to use an external secret management tool as Jenkins own manager is not the most secure. However it is good enough for now, and promotes the best practice to never, ever, store any secret or sensitive information in the pipeline, which is expected to be in version control.

Let me emphasize that: never, **never**, hard-code secrets or sensitive information in the pipeline code (and catastrophically store it in version control).

To finish with the environment preparation, let's add the first stage to the pipeline:

```groovy
    ...
    stages {
        stage('Prepare environment') {
            steps {
                echo '-=- prepare environment -=-'
                echo "APP_NAME: ${APP_NAME}\nAPP_VERSION: ${APP_VERSION}"
                echo "the name for the epheremeral test container to be created is: $EPHTEST_CONTAINER_NAME"
                echo "the base URL for the epheremeral test container is: $EPHTEST_BASE_URL"
                sh 'java -version'
                sh './mvnw --version'
                container('podman') {
                    sh 'podman --version'
                    sh "podman login $CONTAINER_REGISTRY_URL -u $CONTAINER_REGISTRY_CRED_USR -p $CONTAINER_REGISTRY_CRED_PSW"
                }
                container('kubectl') {
                    withKubeConfig([credentialsId: "$KUBERNETES_CLUSTER_CRED_ID"]) {
                        sh 'kubectl version'
                    }
                }
                script {
                    qualityGates = readYaml file: 'quality-gates.yaml'
                }
            }
        }
        ...
    }
    ...
```

This stage will do a few interesting things:

- Download Maven through the Maven wrapper which is included within the repository.
- Verify that Podman can login to the container registry platform.
- Verify that Kubectl can connect successfully with the K3s cluster in Rancher Desktop.
- Read quality gate information from the `quality-gates.yaml` YAML file, available in the repository. This is another good practice to decouple quality gate thresholds from the pipeline code.

And now that the environment is ready, let's continue with the CI tasks.

### 3.2. The pipeline code part 2: Compilation, unit tests and mutation tests

The next three stages will take care of compilation, unit tests and mutation tests tasks:

```groovy
    ...
    stages {
        ...
        stage('Compile') {
            steps {
                echo '-=- compiling project -=-'
                sh './mvnw compile'
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
        ...
    }
    ...
```

It's worth noting that JaCoCo agent is already configured in `pom.xml` file, and therefore running the `test` goal in Maven will also gather the code coverage metrics.

### 3.3. The pipeline code part 3: Package and publish the application

The next two stages will package the application runtime. First, we will package the Spring Boot app with Maven `package` standard goal. Afterwards, Podman will package the app as a container image with the provided definition in the `Dockerfile` file and publish the image to the container registry, so it can be used afterwards to create the ephemeral test environment:

```groovy
    ...
    stages {
        ...
        stage('Package') {
            steps {
                echo '-=- packaging project -=-'
                sh './mvnw package -DskipTests'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }

        stage('Build & push container image') {
            steps {
                echo '-=- build & push container image -=-'
                container('podman') {
                    sh "podman build -t $IMAGE_SNAPSHOT ."
                    sh "podman tag $IMAGE_SNAPSHOT $CONTAINER_REGISTRY_URL/$IMAGE_SNAPSHOT"
                    sh "podman push $CONTAINER_REGISTRY_URL/$IMAGE_SNAPSHOT"
                    sh "podman tag $IMAGE_SNAPSHOT $CONTAINER_REGISTRY_URL/$IMAGE_SNAPSHOT_LATEST"
                    sh "podman push $CONTAINER_REGISTRY_URL/$IMAGE_SNAPSHOT_LATEST"
                }
            }
        }
        ...
    }
    ...
```

As can be seen above, the container image is tagged with a snapshot identifier based on the job build number (so it is unique) as well as a second tag identifying the image as the latest snapshot available.

### 3.4. The pipeline code part 4: Provision the ephemeral test environment

The next stage will request K3s to provision the ephemeral test environment, launching the container image and exposing the ports needed to connect test executors with it.

As this is a very simple app, just `kubectl` commands are enough, but take note that for more complex scenarios, other tools such as Helm or Testcontainers are highly recommended (e.g., to deploy not only a Java app but also a database or any other dependency):

```groovy
    ...
    stages {
        ...
        stage('Run container image') {
            steps {
                echo '-=- run container image -=-'
                container('kubectl') {
                    withKubeConfig([credentialsId: "$KUBERNETES_CLUSTER_CRED_ID"]) {
                        sh "kubectl run $EPHTEST_CONTAINER_NAME --image=$CONTAINER_REGISTRY_URL/$IMAGE_SNAPSHOT --env=JAVA_OPTS=-javaagent:/jacocoagent.jar=output=tcpserver,address=*,port=$APP_JACOCO_PORT --port=$APP_LISTENING_PORT"
                        sh "kubectl expose pod $EPHTEST_CONTAINER_NAME --port=$APP_LISTENING_PORT"
                        sh "kubectl expose pod $EPHTEST_CONTAINER_NAME --port=$APP_JACOCO_PORT --name=$EPHTEST_CONTAINER_NAME-jacoco"
                    }
                }
            }
        }
        ...
    }
    ...
```

It is worth noting that not only the container image is given a tag unique to this build, but also the test container name includes the build number. This allows for parallel execution of builds, i.e. when rapid feedback is required for every commit and/or we have multiple developers in our team.

The port in which the application listens is exposed to be accessible by test executors, but is not published and routed outside the cluster network. Therefore, this is not a test environment that is intended to be used outside of the CI build, such a staging environment.

However, when a staging environment is needed (or any other kind of non-productive environment), it can be easily provisioned with the same image and environment-specific configuration (as needed), effectively providing with production-like environment provisioning capabilities.

The container image already contains the JaCoCo agent runtime, and the application runs with the JaCoCo agent activated and listening in port 6300. Later, during integration tests, the build will connect to that port to dump code coverage information from the ephemeral environment.

### 3.5. The pipeline code part 5: Run integration and performance tests

The following two stages will execute the integration and performance tests, once the application is deployed and available in the ephemeral test environment:

```groovy
    ...
    stages {
        ...
        stage('Integration tests') {
            steps {
                echo '-=- execute integration tests -=-'
                sh "curl --retry 10 --retry-connrefused --connect-timeout 5 --max-time 5 ${EPHTEST_BASE_URL}actuator/health"
                sh "./mvnw failsafe:integration-test failsafe:verify -Dtest.target.server.url=$EPHTEST_BASE_URL"
                sh "java -jar target/dependency/jacococli.jar dump --address $EPHTEST_CONTAINER_NAME-jacoco --port $APP_JACOCO_PORT --destfile target/jacoco-it.exec"
                sh 'mkdir target/site/jacoco-it'
                sh 'java -jar target/dependency/jacococli.jar report target/jacoco-it.exec --classfiles target/classes --xml target/site/jacoco-it/jacoco.xml'
                junit 'target/failsafe-reports/*.xml'
                jacoco execPattern: 'target/jacoco-it.exec'
            }
        }

        stage('Performance tests') {
            steps {
                echo '-=- execute performance tests -=-'
                sh "curl --retry 10 --retry-connrefused --connect-timeout 5 --max-time 5 ${EPHTEST_BASE_URL}actuator/health"
                sh "./mvnw jmeter:configure@configuration jmeter:jmeter jmeter:results -Djmeter.target.host=$EPHTEST_CONTAINER_NAME -Djmeter.target.port=$APP_LISTENING_PORT -Djmeter.target.root=$APP_CONTEXT_ROOT"
                perfReport sourceDataFiles: 'target/jmeter/results/*.csv'
            }
        }
        ...
    }
    ...
```

There a few outstanding pieces that are worth noting.

Before the integration tests are launched, it is good idea to ensure that the application is fully initialised and responding. The `kubectl run` command will return once the pod is scheduled, but this does not mean that the application is up and running and able to respond to requests. With a simple `curl` command it is possible to configure the pipeline to wait for the application to be available.

Integration and performance tests are executing by passing as a parameter the root URL where the application is to be found, including the test container name that will be resolved thanks to the internal cluster DNS, and the configured port.

Code coverage information is being gathered in the test container. Therefore, to have it available for the quality gate and report publishing, the JaCoCo CLI `dump` command is executed. The JaCoCo CLI is available in the `target/dependency` folder as it was included there with the container image and the Maven dependency plugin.

For performance tests, it is possible to include a quality gate in the `perfReport` function, causing the build to fail if any of the thresholds are not passed, as well as flagging a build as unstable. As explained before, the quality gate thresholds are not hard-coded, but included in the `quality-gates.yaml` file in the repository.

To enable the quality gates, use this alternate version of the call to `perfReport`:

```groovy
    ...
    stages {
        ...
        stage('Performance tests') {
            steps {
                ...
                perfReport(
                    sourceDataFiles: 'target/jmeter/results/*.csv',
                    errorUnstableThreshold: qualityGates.performance.throughput.error.unstable,
                    errorFailedThreshold: qualityGates.performance.throughput.error.failed,
                    errorUnstableResponseTimeThreshold: qualityGates.performance.throughput.response.unstable)
            }
        }
        ...
    }
    ...
```

### 3.6. The pipeline code part 6: Promote the container image

At this point of the pipeline, the application has been built, packaged, deployed and tested in different ways. If tests have passed and quality gates are ok, this means that the application container image is ready to be promoted. Typically, a release candidate (RC) or generally available (GA) status and flag is used.

Thos flags are used to differentiate a development build (or snapshot), from a validated, production-ready build (and thus, potentially shippable), or something intermediate that may still require further validation by the business. Depending on how the release process is shaped, and how confident the team is with the automated test suite, the release process may require of user acceptance tests or other kinds of decision making (including the product owner/manager approving the release).

In this case, the container image will be tagged as 'GA' and artifact version number, as well as with a second tag identifying the image as the latest 'GA' available.

```groovy
    ...
    stages {
        ...
        stage('Promote container image') {
            steps {
                echo '-=- promote container image -=-'
                container('podman') {
                    // when using latest or a non-snapshot tag to deploy GA version
                    // this tag push should trigger the change in staging/production environment
                    sh "podman tag $IMAGE_SNAPSHOT $CONTAINER_REGISTRY_URL/$IMAGE_GA"
                    sh "podman push $CONTAINER_REGISTRY_URL/$IMAGE_GA"
                    sh "podman tag $IMAGE_SNAPSHOT $CONTAINER_REGISTRY_URL/$IMAGE_GA_LATEST"
                    sh "podman push $CONTAINER_REGISTRY_URL/$IMAGE_GA_LATEST"
                }
            }
        }
        ...
    }
    ...
```

### 3.7. The pipeline code part 7: Clean up resources

The final piece to set is the `post` block to clean up any resources. In this case, the ephemeral test environment should be removed:

```groovy
pipeline {
    ...
    post {
        always {
            echo '-=- stop test container and remove deployment -=-'
            container('kubectl') {
                withKubeConfig([credentialsId: "$KUBERNETES_CLUSTER_CRED_ID"]) {
                    sh "kubectl delete pod $EPHTEST_CONTAINER_NAME"
                    sh "kubectl delete service $EPHTEST_CONTAINER_NAME"
                    sh "kubectl delete service $EPHTEST_CONTAINER_NAME-jacoco"
                }
            }
        }
    }
}
```

### 3.8. Create the Jenkins job and run the pipeline

Once all the pieces are together it's the time to add the job to Jenkins and execute it.

From Jenkins UI home, click on the `New Item` left menu option. Put a meaningful name to the job (e.g., `workshop-pipelines-<YOUR_NAME_OR_ALIAS>`) and select the `Multibranch Pipeline` job type. Click on the `Ok` button at the bottom.

In the job configuration page, look for the `Branch Sources` section, click on the `Add source` button and select `GitHub` or any other available version control system.

If using a public repository, no credentials will be required. Otherwise, configure the credentials, such as personal access tokens, and use them to access the repository.

Paste the repository HTTPS URL (e.g., `https://github.com/deors/workshop-pipelines`) and use the `Validate` button to ensure that everything is ok.

Click on the `Save` button at the bottom and wait a few seconds for the scan to finish. If everything was configured right, the branches with *Jenkinsfiles* will be found and available to run builds.

Go back to the job main page by clicking in the breadcrumbs at the top and click on the *green triangle* build button to the right of the corresponding branch, and after a few minutes...

Green balls!

## Part 4. Add code inspection and static vulnerability analysis

To add these capabilities to the pipeline we will use SonarQube and OWASP Dependency Check.

### 4.1. Run SonarQube

To run SonarQube, let's use the official image, adding three persistent volumes for SonarQube data, extensions, and search data, a PostgreSQL database, and routing through Traefik (which is available in Rancher Desktop out of the box). An exemplar YAML file with the needed configuration is available in `src/etc` folder:

    kubectl apply -f src/etc/ci-sonarqube.yaml

To verify that the whole deployment was right, use the following command:

    kubectl get all,ingress,pv,pvc

You should see that the additional pod, service, deployment, replica set, ingress, persistent volume, and persistent volume claim objects are created in the cluster.

### 4.2. Configure SonarQube

Thanks to Traefik, we can access the SonarQube UI through `http://localhost/sonarqube`. Alternatively, it is also possible to configure port forwarding in Rancher Desktop.

To integrate SonarQube with Jenkins, the Jenkins plugin must be configured to reach out to the right SonarQube instance when required.

Before configuring that integration, a SonarQube API token must be created. That token is required to authenticate requests coming from Jenkins.

As this is the first run, login to SonarQube using the default credentials: both username and password are simply `admin`. Next, configure a good administrator password.

Click on `Administration` on the top menu and afterwards on `Security` and `Users` in the horizonal menu below. In the `Administrator` user configuration row, there is a menu icon to the right with the label `Update Tokens`. Click on it, and in the pop-up dialog, in the text box below `Generate Tokens` enter `ci-sonarqube-token` (or any other meaningful name), set it to expire in a reasonable time (30 days is fine, but remember that it will expire and it will need to be generated again after that time), and press the `Generate` button. The API token will be shown below. Take note of it, as this is the last time it will be shown in the UI.

Before leaving SonarQube, let's configure the webhook that will be called by SonarQube to let Jenkins know that a requested analysis has finished.

Click on `Administration` on the top menu and afterwards on `Configuration`and `Webhooks` in the horizontal menu below. Click on the `Create` button. Enter `ci-jenkins-webhook` for the webhook name, and for the URL, the Jenkins home URL appending `/sonarqube-webhook`. In our setup, the value should be `http://ci-jenkins:9090/jenkins/sonarqube-webhook`. Click on the `Create` button and configuration on the SonarQube side is ready.

### 4.3. Configure Jenkins integration with SonarQube

Go back to Jenkins. Click on the `Manage Jenkins` left menu option and next click on the `Credentials` center menu option. In the credentials store table, click on the link labeled as `(global)` for the Jenkins global domain.

Next, click on `Add Credentials` in the top menu. In the credential kind select `Secret text`. The secret value is the API token just generated. The secret id can be `ci-sonarqube-token` (or another good name of your choice). Press `Create` when finished to save the credentials in the store.

Next, let's configure the SonarQube server integration. Go back to the dashboard, click on the `Manage Jenkins` left menu option and next click on the `System` center menu option. Scroll down until the section `SonarQube Servers` is visible. Click on the checkbox to allow injection of server configuration.

Next, let's add the SonarQube instance name and URL. To ensure that the right server is used by the pipeline use `ci-sonarqube` for the instance name. If the selected name is different, it should match the name referenced in the pipeline code later. For the server URL, use the SonarQube home URL. In our setup, the value should be `http://ci-sonarqube:9000/sonarqube` using the internal cluster DNS. Finally, for the server authentication token, use the API token stored in the `ci-sonarqube-token` credential created before. Click on the `Save` button and configuration on the Jenkins side is ready.

### 4.4. The pipeline code part 8: Code inspection and quality gate

Now that Jenkins and SonarQube are both configured, let's add a stage to the pipeline to execute code inspection with SonarQube. SonarQube analysis includes static security vulnerability analysis, as well as additional analysis provided by third-party tools (enabled through plugins) such as PMD or SpotBugs. Additionally, any compound quality gate defined in SonarQube for the technology or project will also be checked and will cause the build to stop if not passed.

Where should this stage be placed? This is a very good question. Some people recommends to run the code analysis after the unit tests and before packaging the application. This is a good general approach but it has a disadvantage: quality gates cannot use integration test results (e.g., the code coverage gathered after Selenium tests are executed).

Considering that, I recommend to put this stage after integration & performance tests, and before the container image is promoted to 'GA' status.

Take into consideration that the SonarQube instance referred in the call to the function `withSonarQubeEnv` must match the instance configured in Jenkins as explained above.

```groovy
    ...
    stages {
        ...
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

It's worth noting that the code analysis and calculation of the quality gate by SonarQube is an asynchronous process. Depending on SonarQube server load, it might take some time for results to be available, and as a design decision, the `sonar:sonar` goal will not wait, blocking the build, until then. This has the beneficial side effect that the Jenkins executor is not blocked and other builds might be built in the meantime, maximizing the utilization of Jenkins build farm resources.

The pipeline code therefore sets a wait time for SonarQube to 'call back home' using the configured webhook, and unpause to continue evaluating the quality gate results, as well as any further stages pending in the pipeline.

The default behavior for SonarQube quality gate, as coded in the `waitForQualityGate` function, is to break the build in case any of the thresholds defined in the gate is not achieved.

### 4.5. The pipeline code part 9: Software composition analysis

Besides the static vulnerability analysis provided by SonarQube it is strongly recommended to check dependencies for known security vulnerabilities. Our code may be 100% clean of known vulnerabilities and be critically exposed to exploits due to the usage of libraries with unpatched vulnerabilities.

A popular open-source tool for software composition analysis is OWASP Dependency Check. Let's add a stage before packaging the application to run the scan and publish results in Jenkins:

```groovy
    ...
    stages {
        ...
        stage('Software composition analysis') {
            steps {
                echo '-=- run software composition analysis -=-'
                sh './mvnw dependency-check:check'
                dependencyCheckPublisher
            }
        }
        ...
    }
    ...
```

OWASP Dependency Check also allows to set up a quality gate in the call to the `dependencyCheckPublisher` function, causing the build to fail if any of the thresholds are not passed, as well as flagging the build as unstable.

As of the time of this update, Spring Boot 2.7.12 has 10 known vulnerabilities without a patch. The quality gate shown below will accept those vulnerabilities (2 critical, 1 high, 7 medium severity) and will fail in the case that additional vulnerabilities are detected.

However, the publisher function will not break the build immediately when found vulnerabilities exceed the configured threshold. To close that gap, a simple script can be added to the scan step:

```groovy
    ...
    stages {
        ...
        stage('Software composition analysis') {
            steps {
                echo '-=- run software composition analysis -=-'
                sh './mvnw dependency-check:check'
                dependencyCheckPublisher(
                    failedTotalCritical: qualityGates.security.dependencies.critical.failed,
                    unstableTotalCritical: qualityGates.security.dependencies.critical.unstable,
                    failedTotalHigh: qualityGates.security.dependencies.high.failed,
                    unstableTotalHigh: qualityGates.security.dependencies.high.unstable,
                    failedTotalMedium: qualityGates.security.dependencies.medium.failed,
                    unstableTotalMedium: qualityGates.security.dependencies.medium.unstable)
                script {
                    if (currentBuild.result == 'FAILURE') {
                        error('Dependency vulnerabilities exceed the configured threshold')
                    }
                }
            }
        }
        ...
    }
    ...
```

## Part 5. Add web application performance analysis

To add this capability to the pipeline we will use Lighthouse CI.

### 5.1. Run Lighthouse CI

To run Lighthouse CI, let's use the official image, adding a persistent volume for Lighthouse CI ata, and routing through Traefik (which is available in Rancher Desktop out of the box). An exemplar YAML file with the needed configuration is available in `src/etc` folder:

    kubectl apply -f src/etc/ci-lighthouse.yaml

To verify that the whole deployment was right, use the following command:

    kubectl get all,ingress,pv,pvc

You should see that the additional pod, service, deployment, replica set, ingress, persistent volume, and persistent volume claim objects are created in the cluster.

IMPORTANT NOTE: At the time of this update, with Lighthouse CI 0.12.0 the routing is not working because Lighthouse CI server does not support to run behind a proxy in non-root paths as it uses absolute URLs. Due to that, enable port forwarding in Rancher Desktop UI to access Lighthouse CI UI and explore analysis results.

### 5.2. Configure Lighthouse CI

Before running an analysis with Lighthouse CI it is required to create a project and obtain a token to integrate the collection tool with the server.

The simplest way to launch the CLI is directly from a container, by running this command:

    kubectl run lhci --image=patrickhulce/lhci-client:0.12.0 --stdin --tty --rm --command -- lhci wizard

A simple console-based wizard will launch. Select the highlighted option `new-project`, enter the Lighthouse CI server URL as seen from inside the cluster (it should be `http://ci-lighthouse:9001`), enter the name of the project, the Git repository URL, and the name of the main branch.

If you have Node.js installed locally in your workstation you can also install `lhci` and run the wizard locally:

    npm install -g @lhci/cli@0.12.0
    lchi wizard

In this case, remember to use `localhost` and the port number enabled in port forwarding to connect with Lighthouse CI server.

### 5.3. Configure Jenkins integration with Lighthouse CI

In Jenkins a couple of secrets will be configured, as they are used by the pipeline to get the server URL and project token.

Go to Jenkins credentials page. Click on `Add Credentials` in the top menu. In the credential kind select `Secret text`. The secret value is the server URL, `http://ci-lighthouse:9001` if following this guide. The secret id will be `ci-lighthouse-url` (or another good name of your choice but keep in mind that it should match the secret id used in the pipeline). Press `Create` when finished to save the token in the store.

Repeat for the second secret. The secret value is the analysis token just generated with `lhci wizard`. The secret id will be `ci-lighthouse-token-<<YOUR_PROJECT_NAME>>` (again, remember to match the id if you change it). Press `Create` when finished to save the token in the store.

### 5.4. The pipeline code part 10: Web application performance analysis

To be able to run Lighthouse CI from the pipeline we must add a new container to the build pod:

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
      ...
    - name: lhci
      image: docker.io/patrickhulce/lhci-client:0.12.0
      command:
        - cat
      tty: true
      securityContext:
        runAsUser: 0
        privileged: true
  volumes:
    - name: m2-cache
...
```

In the `environment` block, let's get the two credentials that will be used later when the analysis is run:


```groovy
pipeline {
    ...
    environment {
        ...
        // credentials
        KUBERNETES_CLUSTER_CRED_ID = 'k3s-lima-vm-kubeconfig'
        CONTAINER_REGISTRY_CRED = credentials("docker-hub-$IMAGE_ORG")
        LIGHTHOUSE_TOKEN = credentials("ci-lighthouse-token-$APP_NAME")
        LIGHTHOUSE_URL = credentials('ci-lighthouse-url')
    }
    ...
}
```

Executing an analysis with Lighthouse CI is a bit more elaborated than with other tools. While other analysis/scan tools typically infer the scope of the analysis/scan automatically from the contents of the workspace (that is, the checked out repository), Lighthouse CI requires to explicitely receive the page endpoint that must be analyzed.

This is not a big deal, though, as using Groovy code it is very simple to iterate the call to Lighthouse CI over a list of page URLs. I would highly recommended to have that list externalized in a YAML or JSON file in the repository.

```groovy
    ...
    stages {
        ...
        stage('Web page performance analysis') {
            steps {
                echo '-=- execute web page performance analysis -=-'
                container('lhci') {
                    sh """
                      cd $WORKSPACE
                      git config --global --add safe.directory $WORKSPACE
                      export LHCI_BUILD_CONTEXT__CURRENT_BRANCH=$GIT_BRANCH
                      lhci collect --collect.settings.chromeFlags='--no-sandbox' --url ${EPHTEST_BASE_URL}hello
                      lhci upload --token $LIGHTHOUSE_TOKEN --serverBaseUrl $LIGHTHOUSE_URL --ignoreDuplicateBuildFailure
                    """
                }
            }
        }
        ...
    }
    ...
```

As Lighthouse CI uses Git to extract relevant project information, it is required to set in Git the configuration parameter seen above, `safe.directory`, to allow for that.

The analysis process has two steps: first the Lighthouse CI CLI tool will analyze and collect the relevant data, and next the data is uploaded to the Lighthouse CI server so it is available to the whole team.

It is possible to set quality gates for Lighthouse CI. This is done by providing the tool with settings to set the threshold level. For more information on how this might be configured, you may check Lighthouse CI documentation here: [https://googlechrome.github.io/lighthouse-ci/docs/configuration.html#assert]

Many times it would suffice to set the assertion level to the recommended rule set, using the following `lhci assert` command between `lhci collect` and `lhci upload`:

```groovy
    ...
    stages {
        ...
        stage('Web page performance analysis') {
            steps {
                echo '-=- execute web page performance analysis -=-'
                container('lhci') {
                    sh """
                      cd $WORKSPACE
                      git config --global --add safe.directory $WORKSPACE
                      export LHCI_BUILD_CONTEXT__CURRENT_BRANCH=$GIT_BRANCH
                      lhci collect --collect.settings.chromeFlags='--no-sandbox' --url ${EPHTEST_BASE_URL}hello
                      lhci assert --preset=lighthouse:recommended --includePassedAssertions
                      lhci upload --token $LIGHTHOUSE_TOKEN --serverBaseUrl $LIGHTHOUSE_URL --ignoreDuplicateBuildFailure
                    """
                }
            }
        }
        ...
    }
    ...
```

And with this change, let's launch the pipeline again and check whether everything went well end-to-end.

Enjoy!

--- END OF WORKSHOP ---

## APPENDIX A. Explaining stuff in Maven's pom.xml

### A.1. Adding JaCoCo agent to gather code coverage metrics during tests

One of the actions to be done along the pipeline, is to gather code coverage metrics when unit tests and integration tests are executed. To do that, there are a few actions needed in preparation for the task.

First, the JaCoCo agent must be added as a Maven dependency in `pom.xml`:

```xml
    <dependencies>
        ...
        <dependency>
            <groupId>org.jacoco</groupId>
            <artifactId>org.jacoco.agent</artifactId>
            <version>0.8.10</version>
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
                    <argLine>-javaagent:${settings.localRepository}/org/jacoco/org.jacoco.agent/0.8.10/org.jacoco.agent-0.8.10-runtime.jar=destfile=${project.build.directory}/jacoco.exec</argLine>
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

For integration tests, the code coverage setup is a bit more complicated. Instead of enabling the agent in the test executor, the process that must have the agent enabled is the test server. The former approach works for unit tests because the same JVM process holds both the test code and the code for the application being tested. However for integration tests, the test execution is a separate process from the application being tested.

As the application is packaged and runs as a container image, the agent file must be present at the image build time. Later, during the execution of integration tests, the JaCoCo CLI tool will be needed to dump the coverage data from the test server. To do that, both dependencies will be copied into the expected folder with the help of the Maven Dependency plugin:

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
                                    <version>0.8.10</version>
                                    <classifier>runtime</classifier>
                                    <destFileName>jacocoagent.jar</destFileName>
                                </artifactItem>
                                <artifactItem>
                                    <groupId>org.jacoco</groupId>
                                    <artifactId>org.jacoco.cli</artifactId>
                                    <version>0.8.10</version>
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

And finally, the JaCoCo agent needs to be copied into the container image. Edit the file `Dockerfile` and add a new `ADD` instruction after `VOLUME`:

```dockerfile
    ...
    VOLUME /tmp
    ADD target/dependency/jacocoagent.jar jacocoagent.jar
    ...
```

### A.2. Configuring Failsafe for integration test execution

Although Maven Surefire plugin is enabled by default, Failsafe, the Surefire twin for integration tests, is disabled by default. To enable Failsafe, its targets must be called explicitely or alternatively may be binded to the corresponding lifecycle goals. To better control its execution in the pipeline it is preferred to keep Failsafe disabled by default:

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

### A.3. Adding performance tests with Apache JMeter

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

### A.4. Configuring mutation testing

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
                <version>1.14.1</version>
                <configuration>
                    <excludedTestClasses>
                        <param>*ApplicationTest</param>
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
                        <version>1.2.0</version>
                    </dependency>
                </dependencies>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

Due to how Pitest plugin works, it will fail when there are no mutable tests i.e. no strict unit tests. Considering this, the pipelines corresponding to services without mutable tests should skip the execution of Pitest.

### A.5. Configuring dependency vulnerability scans with OWASP

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
                <version>8.3.1</version>
                <configuration>
                    <format>ALL</format>
                </configuration>
            </plugin>
            ...
        </plugins>
        ...
    </build>
```

--- EOF ---
