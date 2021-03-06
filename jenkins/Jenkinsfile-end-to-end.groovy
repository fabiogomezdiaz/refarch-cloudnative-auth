/*
    To learn how to use this sample pipeline, follow the guide below and enter the
    corresponding values for your environment and for this repository:
    - https://github.com/fabiogomezdiaz/refarch-cloudnative-devops-kubernetes
*/

// Environment
def clusterURL = env.CLUSTER_URL
def clusterAccountId = env.CLUSTER_ACCOUNT_ID
def clusterCredentialId = env.CLUSTER_CREDENTIAL_ID ?: "cluster-credentials"

// Pod Template
def podLabel = "auth"
def cloud = env.CLOUD ?: "kubernetes"
def registryCredsID = env.REGISTRY_CREDENTIALS ?: "registry-credentials-id"
def serviceAccount = env.SERVICE_ACCOUNT ?: "jenkins"
def tls = env.TLS ?: "" // Set to "--tls" for IBM Cloud Private

// Pod Environment Variables
def namespace = env.NAMESPACE ?: "default"
def registry = env.REGISTRY ?: "docker.io"
def imageName = env.IMAGE_NAME ?: "fabiogomezdiaz/bluecompute-auth"
def serviceLabels = env.SERVICE_LABELS ?: "app=auth,tier=backend" //,version=v1"
def microServiceName = env.MICROSERVICE_NAME ?: "auth"
def servicePort = env.MICROSERVICE_PORT ?: "8083"
def managementPort = env.MANAGEMENT_PORT ?: "8093"

// HS256_KEY Secret
def hs256Key = env.HS256_KEY

// URL for external inventory service
def customerURL = env.CUSTOMER_URL ?: "http://customer-customer:8082"

/*
  Optional Pod Environment Variables
 */
def helmHome = env.HELM_HOME ?: env.JENKINS_HOME + "/.helm"

podTemplate(label: podLabel, cloud: cloud, serviceAccount: serviceAccount, envVars: [
        envVar(key: 'CLUSTER_URL', value: clusterURL),
        envVar(key: 'CLUSTER_ACCOUNT_ID', value: clusterAccountId),
        envVar(key: 'NAMESPACE', value: namespace),
        envVar(key: 'REGISTRY', value: registry),
        envVar(key: 'IMAGE_NAME', value: imageName),
        envVar(key: 'SERVICE_LABELS', value: serviceLabels),
        envVar(key: 'MICROSERVICE_NAME', value: microServiceName),
        envVar(key: 'MICROSERVICE_PORT', value: servicePort),
        envVar(key: 'MANAGEMENT_PORT', value: managementPort),
        envVar(key: 'HS256_KEY', value: hs256Key),
        envVar(key: 'CUSTOMER_URL', value: customerURL),
        envVar(key: 'HELM_HOME', value: helmHome)
    ],
    volumes: [
        hostPathVolume(mountPath: '/home/gradle/.gradle', hostPath: '/tmp/jenkins/.gradle'),
        emptyDirVolume(mountPath: '/var/lib/docker', memory: false)
    ],
    containers: [
        containerTemplate(name: 'jdk', image: 'fabiogomezdiaz/openjdk-bash:alpine', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker', image: 'fabiogomezdiaz/docker:18.09-dind', privileged: true),
        containerTemplate(name: 'kubernetes', image: 'fabiogomezdiaz/jenkins-slave-utils:3.1.2', ttyEnabled: true, command: 'cat')
  ]) {

    node(podLabel) {
        checkout scm

        // Local
        container(name:'jdk', shell:'/bin/bash') {
            stage('Local - Build and Unit Test') {
                sh """
                #!/bin/bash
                ./gradlew build
                """
            }
            stage('Local - Run and Test') {
                sh """
                #!/bin/bash

                JAVA_OPTS="-DcustomerService.url=${CUSTOMER_URL}"

                java \${JAVA_OPTS} -jar build/libs/micro-auth-0.0.1.jar &

                PID=`echo \$!`

                # Let the application start
                bash scripts/health_check.sh "http://127.0.0.1:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT}

                # Kill process
                kill \${PID}
                """
            }
        }

        // Docker
        container(name:'docker', shell:'/bin/bash') {
            stage('Docker - Build Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                docker build -t \${IMAGE} .
                """
            }
            stage('Docker - Run and Test') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                # Kill Container if it already exists
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true

                # Start Container
                echo "Starting ${MICROSERVICE_NAME} container"
                set +x
                docker run --name ${MICROSERVICE_NAME} -d \
                    -p ${MICROSERVICE_PORT}:${MICROSERVICE_PORT} \
                    -p ${MANAGEMENT_PORT}:${MANAGEMENT_PORT} \
                    -e SERVICE_PORT=${MICROSERVICE_PORT} \
                    -e HS256_KEY=${HS256_KEY} \
                    -e CUSTOMER_URL=${CUSTOMER_URL} \
                    \${IMAGE}
                set -x

                # Check that application started successfully
                docker ps

                # Check the logs
                docker logs -f ${MICROSERVICE_NAME} &
                PID=`echo \$!`

                # Get the container IP Address
                CONTAINER_IP=`docker inspect ${MICROSERVICE_NAME} | jq -r '.[0].NetworkSettings.IPAddress'`

                # Let the application start
                bash scripts/health_check.sh "http://\${CONTAINER_IP}:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh \${CONTAINER_IP} ${MICROSERVICE_PORT}

                # Kill process
                kill \${PID}

                # Kill Container
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true
                """
            }
            stage('Docker - Push Image to Registry') {
                withCredentials([usernamePassword(credentialsId: registryCredsID,
                                               usernameVariable: 'USERNAME',
                                               passwordVariable: 'PASSWORD')]) {
                    sh """
                    #!/bin/bash

                    # Get image
                    if [ "${REGISTRY}" == "docker.io" ]; then
                        IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                    else
                        IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                    fi

                    docker login -u ${USERNAME} -p ${PASSWORD} ${REGISTRY}

                    docker push \${IMAGE}
                    """
                }
            }
        }

        // Kubernetes
        container(name:'kubernetes', shell:'/bin/bash') {
            stage('Initialize helm') {
                sh """
                echo "Initializing Helm ..."
                export HELM_HOME=${HELM_HOME}
                helm init -c
                """
            }
            // Initialize cloudctl for IBM Cloud Private
            if (env.TLS && env.TLS == "--tls") {
                stage ('Initialize cloudctl') {
                    withCredentials([usernamePassword(credentialsId: clusterCredentialId,
                                                   passwordVariable: 'CLUSTER_PASSWORD',
                                                   usernameVariable: 'CLUSTER_USERNAME')]) {
                        sh """
                        echo "Login with cloudctl ..."
                        cloudctl login -a ${CLUSTER_URL} -u ${CLUSTER_USERNAME}  -p "${CLUSTER_PASSWORD}" -c ${CLUSTER_ACCOUNT_ID} -n ${NAMESPACE} --skip-ssl-validation
                        """
                    }
                }
            }
            stage('Kubernetes - Deploy new Docker Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}
                fi

                # Helm Parameters
                if [ "${DEPLOY_NEW_VERSION}" == "true" ]; then
                    NAME="${MICROSERVICE_NAME}-v${env.BUILD_NUMBER}"
                    VERSION_LABEL="--set labels.version=v${env.BUILD_NUMBER}"
                else
                    NAME="${MICROSERVICE_NAME}"
                fi

                echo "Installing chart/${MICROSERVICE_NAME} chart with name \${NAME} and waiting for pods to be ready"

                set +x
                helm upgrade --install \${NAME} --namespace ${NAMESPACE} \${VERSION_LABEL} \
                    --set fullnameOverride=\${NAME} \
                    --set image.repository=\${IMAGE} \
                    --set image.tag=${env.BUILD_NUMBER} \
                    --set service.externalPort=${MICROSERVICE_PORT} \
                    --set hs256key.secret="${HS256_KEY}" \
                    --set customer.url="${CUSTOMER_URL}" \
                    chart/${MICROSERVICE_NAME} --wait ${TLS}
                set -x
                """
            }
            stage('Kubernetes - Test') {
                sh """
                #!/bin/bash

                # Get deployment
                if [ "${DEPLOY_NEW_VERSION}" == "true" ]; then
                    QUERY_LABELS="${SERVICE_LABELS},version=v${env.BUILD_NUMBER}"
                else
                    QUERY_LABELS="${SERVICE_LABELS}"
                fi

                DEPLOYMENT=`kubectl --namespace=${NAMESPACE} get deployments -l \${QUERY_LABELS} -o name | head -n 1`

                # Wait for deployment to be ready
                kubectl --namespace=${NAMESPACE} rollout status \${DEPLOYMENT}

                # Port forwarding & logs
                kubectl --namespace=${NAMESPACE} port-forward \${DEPLOYMENT} ${MICROSERVICE_PORT} ${MANAGEMENT_PORT} &
                kubectl --namespace=${NAMESPACE} logs -f \${DEPLOYMENT} &
                echo "Sleeping for 3 seconds while connection is established..."
                sleep 3

                # Let the application start
                bash scripts/health_check.sh "http://127.0.0.1:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT}

                # Kill port forwarding
                killall kubectl || true
                """
            }
        }
    }
}
