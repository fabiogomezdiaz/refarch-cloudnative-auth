# refarch-cloudnative-micro-auth: Secure REST API with OAuth 2.0 and Authorization Service

*This project is part of the 'IBM Cloud Native Reference Architecture' suite, available at
https://github.com/fabiogomezdiaz/refarch-cloudnative-kubernetes/tree/master*

## Table of Contents
  * [Introduction](#introduction)
    + [Interaction with Identity Provider (Auth Microservice)](#interaction-with-identity-provider-auth-microservice)
    + [Interaction with Resource Server API](#interaction-with-resource-server-api)
  * [REST API Endpoints](#rest-api-endpoints)
  * [Deploy Auth Application to Kubernetes Cluster](#deploy-auth-application-to-kubernetes-cluster)
  * [Validate Auth Service](#validate-auth-service)
    + [Validate the password flow of the authorization service](#validate-the-password-flow-of-the-authorization-service)
    + [Validate the implicit flow of the authorization service](#validate-the-implicit-flow-of-the-authorization-service)
  * [Deploy Auth Application on Docker](#deploy-auth-application-on-docker)
    + [Setup: Deploy Customer Docker Container](#setup-deploy-customer-docker-container)
    + [Deploy the Auth Docker Container](#deploy-the-auth-docker-container)
  * [Run Auth Service application on localhost](#run-auth-service-application-on-localhost)
  * [Optional: Setup CI/CD Pipeline](#optional-setup-cicd-pipeline)
  * [Conclusion](#conclusion)
  * [Contributing](#contributing)
    + [GOTCHAs](#gotchas)
    + [Contributing a New Chart Package to Microservices Reference Architecture Helm Repository](#contributing-a-new-chart-package-to-microservices-reference-architecture-helm-repository)

## Introduction
This project demonstrates how to authenticate the API user as well as enable OAuth 2.0 authorization for all OAuth protected APIs in the BlueCompute reference application. The Spring Authorization Server is used as an OAuth provider; the BlueCompute reference application delegates authentication and authorization to this component, which verifies credentials using the [Auth Microservice](https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-auth/tree/master).

Here is an overview of the project's features:
- Leverage [`Spring Boot`](https://projects.spring.io/spring-boot/) framework to build a Microservices application.
- Spring-based [`Authorization Server`](https://spring.io/projects/spring-security-oauth) application that handles user authentication and authorization.
- Uses [`Spring Feign Client`](https://cloud.spring.io/spring-cloud-netflix/multi/multi_spring-cloud-feign.html) to call Auth Microservice to validate login credentials.
- Return a signed [JWT](https://jwt.io) Bearer token back to caller for identity propagation and authorization
- Uses [`Docker`](https://docs.docker.com/) to package application binary and its dependencies.
- Uses [`Helm`](https://helm.sh/) to package application and Auth deployment configuration and deploy to a [`Kubernetes`](https://kubernetes.io/) cluster.

### Interaction with Identity Provider (Auth Microservice)

![Application Architecture](static/diagrams/auth.png?raw=true)

* The Authorization microservice leverages the [Auth Microservice](https://github.com/fabiogomezdiaz/refarch-cloudnative-auth/tree/master) as an identity provider.
* When username/password is passed in, the Authorization microservice calls the Auth microservice using Spring Feign Client.
* Authorization microservice checks the password against the password returned by the Auth API.  If it matches, `HTTP 200` is returned to indicate that the username/password are valid, `HTTP 401` is returned to indicate that the username/password is invalid.

  *Note that this is only meant as a demonstration on how to implement a custom identity provider and shouldn't be used in production, as the passwords are returned in compared in plaintext instead of using secure one-way hashes.*

### Interaction with Resource Server API
![Application Architecture](static/diagrams/auth_orders.png?raw=true)

* When a client wishes to acquire an OAuth token to call a protected API, it calls the OAuth Provider (Authorization microservice) token endpoint with the username/password of the user and requests a token with scope `blue`.
* Authorization microservice will call the Auth microservice to get the Auth object assicated with the username/password and perform the validation.
* If the username/password are valid, `HTTP 200` is returned, along with a JWT (signed using a HS256 shared secret) in the JSON response under `access_token` which contains the auth ID of the user passed in in the `user_name` claim.
* The client uses the JWT in the `Authorization` header as a bearer token to call other Resource Servers that have OAuth protected API (such as the [Orders microservice](https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-orders/tree/master)).
* The service implementing the REST API verifies that the JWT is valid and signed using the shared secret, then extracts the `user_name` claim from the JWT to identify the caller.
* The JWT is encoded with scope `blue` and the the expiry time in `exp`; once the token is generated there is no additional interaction between the Resource Server and the OAuth server.

## REST API Endpoints
Following the [OAuth 2.0 specification](https://tools.ietf.org/html/rfc6749), the Authorization server exposes both an authorization URI and a token URI.

- GET `/oauth/authorize`
- POST `/oauth/token`

The BlueCompute reference application supports the following clients and grant types:

- The [BlueCompute Web Application](https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-web/tree/master) using client ID `bluecomputeweb` and client secret `bluecomputewebs3cret` supports OAuth 2.0 Password grant type.
- The [BlueCompute Mobile Application](https://github.com/fabiogomezdiaz/refarch-cloudnative-bluecompute-mobile) using client ID `bluecomputemobile` and client secret `bluecomputemobiles3cret` supports OAuth 2.0 Implicit grant type.

The BlueCompute application has one scope, `blue`.

## Deploy Auth Application to Kubernetes Cluster
In this section, we are going to deploy the Auth Application, along with a Customer service, to a Kubernetes cluster using Helm. To do so, follow the instructions below:
```bash
# Add helm repos for Customer and CouchDB Charts
helm repo add ibmcase https://raw.githubusercontent.com/fabiogomezdiaz/refarch-cloudnative-kubernetes/master/docs/charts
helm repo add incubator http://storage.googleapis.com/kubernetes-charts-incubator

# Install CouchDB Chart
helm upgrade --install couchdb \
  --version 0.2.2 \
  --set fullnameOverride=customer-couchdb \
  --set service.externalPort=5985 \
  --set createAdminSecret=true \
  --set adminUsername=user \
  --set adminPassword=passw0rd \
  --set clusterSize=1 \
  --set persistentVolume.enabled=false \
  incubator/couchdb

# Install Customer Chart
helm upgrade --install customer ibmcase/customer

# Go to Chart Directory
cd chart/auth

# Deploy Auth to Kubernetes cluster
helm upgrade --install auth --set service.type=NodePort,customer.url=http://customer-customer:8082 .
```

The last command will give you instructions on how to access/test the Auth application. Please note that before the Auth application starts, the Customer deployment must be fully up and running, which normally takes a couple of minutes. With Kubernetes [Init Containers](https://kubernetes.io/docs/concepts/workloads/pods/init-containers/), the Auth Deployment polls for Customer readiness status so that Auth can start once Customer is ready, or error out if Customer fails to start.

To check and wait for the deployment status, you can run the following command:
```bash
kubectl get deployments -w
NAME                  DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
auth-auth             1         1         1            1           10h
```

The `-w` flag is so that the command above not only retrieves the deployment but also listens for changes. If you a 1 under the `CURRENT` column, that means that the auth app deployment is ready.

In the following section you validate that the Auth chart was deployed successfully. Make sure to use the `${NODE_IP}` and `${PORT}` that you get from the chart install output instead of `localhost` and `8083`.

## Validate Auth Service

### Validate the password flow of the authorization service
The [Web Application](https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-web/tree/master) uses the password flow to obtain a password token.  It uses Client ID `bluecomputeweb` and Client Secret `bluecomputewebs3cret`.  For a user `user` with password `passw0rd`, run the following to obtain an access token with scope `blue`:
```bash
curl -i \
   -X POST \
   -u bluecomputeweb:bluecomputewebs3cret \
   http://localhost:8083/oauth/token?grant_type=password\&username=user\&password=passw0rd\&scope=blue

HTTP/1.1 200 OK
Date: Thu, 23 Aug 2018 20:22:50 GMT
X-Content-Type-Options: nosniff
X-XSS-Protection: 1; mode=block
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
X-Application-Context: auth-microservice:8083
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json;charset=UTF-8
Transfer-Encoding: chunked
Server: Jetty(9.2.16.v20160414)

{"access_token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE1MzUwOTg5NzEsInVzZXJfbmFtZSI6ImM1MmU4NDJmYjllNjQ3Y2Y4ZGFhNDMyMDZmOTAyZTY2IiwiYXV0aG9yaXRpZXMiOlsiUk9MRV9VU0VSIl0sImp0aSI6ImNjNGQ1MWYzLTk1MDItNGI0Yy04ZDY1LThkY2VmNjU0MmM5ZCIsImNsaWVudF9pZCI6ImJsdWVjb21wdXRld2ViIiwic2NvcGUiOlsiYmx1ZSJdfQ.TWOqO-rFP5V7QXEiLqKByxXMQzQVEEiSDnLwSHOLE4c","token_type":"bearer","refresh_token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyX25hbWUiOiJjNTJlODQyZmI5ZTY0N2NmOGRhYTQzMjA2ZjkwMmU2NiIsInNjb3BlIjpbImJsdWUiXSwiYXRpIjoiY2M0ZDUxZjMtOTUwMi00YjRjLThkNjUtOGRjZWY2NTQyYzlkIiwiZXhwIjoxNTM3NjQ3NzcxLCJhdXRob3JpdGllcyI6WyJST0xFX1VTRVIiXSwianRpIjoiZjNjZDZhMTMtOTBmZi00YzUyLTg2ZjQtMzBhMTE2MDhlMDEyIiwiY2xpZW50X2lkIjoiYmx1ZWNvbXB1dGV3ZWIifQ.HeD8hZXJeoSuXdET168c4-ycZjCRTcs80grgqBkg6Lg","expires_in":43199,"scope":"blue","jti":"cc4d51f3-9502-4b4c-8d65-8dcef6542c9d"}
```

The response JSON returned will contain an `access_token`.  Use the debugger at [jwt.io](https://jwt.io) to decode the token and validate the signature by pasting the `access_token` into the `Encoded` text field, and pasting the HS256 shared secret in the `Verify Signature` text box.  You should observe the `client_id` and `scope` claims in the payload correspond to the client ID and scope passed in on the request query, the `user_name` corresponds to the auth ID of `user` returned from the Auth Service, and the signature is verified.

### Validate the implicit flow of the authorization service
The [Mobile application](https://github.com/fabiogomezdiaz/refarch-cloudnative-bluecompute-mobile) uses the implicit flow to create a token by opening a browser and retrieving the OAuth token once the authorization flow is complete.

To validate that this works, open a browser window and navigate to the following URL.  This requests the token with scope `blue` using the client id `bluecomputemobile` with the client secret `bluecomputemobiles3cret`. When the full authorization flow is completed, the authorization server will redirect the browser to `https://ibm.com`.

* http://localhost:8083/oauth/authorize?client_id=bluecomputemobile&client_secret=bluecomputemobiles3cret&response_type=token&redirect_uri=https://ibm.com

![Application Architecture](static/images/1_enter_credentials.png?raw=true)

The login form is shown with the username and password.  Enter the username and password `user` and `passw0rd` respectively, and the browser is taken to the authorization page. When authorization is granted, the browser is taken to the URL with the access token as a query parameter.

![Application Architecture](static/images/2_oauth_approval.png?raw=true)

As with the password flow, you can use [jwt.io](https://jwt.io) to verify the token's scope and claims.

## Deploy Auth Application on Docker
You can also run the Auth Application locally on Docker. Before we show you how to do so, you will need to have a running Customer deployment running somewhere.

### Setup: Deploy Customer Docker Container
The easiest way to to setup the customer service is with a docker container. To do so, follow this guide from the customer service GitHub page:
https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-customer/tree/master#deploy-customer-application-on-docker

Then you must create a customer record by following the setup section and step 1 in the following guide:
https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-customer/tree/master#validate-the-customer-microservice-api

Lastly, you must obtain the customer container's IP address:
```bash
# Get the Customer Container's IP Address
docker inspect customer | grep "IPAddress"
            "SecondaryIPAddresses": null,
            "IPAddress": "172.17.0.2",
                    "IPAddress": "172.17.0.2",
```
Make sure to select the IP Address in the `IPAddress` field. You will use this IP address when deploying the Auth container.

### Deploy the Auth Docker Container
To deploy the Auth container, run the following commands:
```bash
# Build the Docker Image
docker build -t auth .

# Start the Auth Container
docker run --name auth \
    -e CUSTOMERSERVICE_URL=http://${CUSTOMER_IP_ADDRESS}:8082 \
    -e HS256_KEY=${HS256_KEY} \
    -p 8083:8083 \
    -d auth
```

Where:
* `${CUSTOMER_IP_ADDRESS}` is the IP address of the Customer container, which is only accessible from the Docker container network.
* `${HS256_KEY}` is the 2048-bit secret, which must match that of the customer service.
  + [Here](https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-customer/tree/master#b-create-a-temporary-hs256-shared-secret) the key that's used in the customer service, along with instructions on how to create your own.

You have successfully deployed the Auth container! To validate, follow the instructions in the [Validate Auth Service](#validate-auth-service) section.

## Run Auth Service application on localhost
In this section you will run the Spring Boot application on your local workstation. Before we show you how to do so, you will need to deploy a Customer Docker container as shown in the [Setup: Deploy Customer Docker Container](#setup-deploy-customer-docker-container) section.

Once Customer is ready, we can run the Spring Boot Auth application locally as follows:

1. Build the application:
```bash
./gradlew build
```

2. Run the application on localhost:
```bash
java -jar build/libs/micro-auth-0.0.1.jar
```

You have successfully deployed the Auth service locally! To validate, follow the instructions in the [Validate Auth Service](#validate-auth-service) section.

## Optional: Setup CI/CD Pipeline
If you would like to setup an automated Jenkins CI/CD Pipeline for this repository, we provided a sample [Jenkinsfile](Jenkinsfile), which uses the [Jenkins Pipeline](https://jenkins.io/doc/book/pipeline/) syntax of the [Jenkins Kubernetes Plugin](https://github.com/jenkinsci/kubernetes-plugin) to automatically create and run Jenkis Pipelines from your Kubernetes environment.

To learn how to use this sample pipeline, follow the guide below and enter the corresponding values for your environment and for this repository:
* https://github.com/fabiogomezdiaz/refarch-cloudnative-devops-kubernetes

## Conclusion
You have successfully deployed and tested the Auth Microservice both on a Kubernetes Cluster and in local Docker Containers.

To see the Auth app working in a more complex microservices use case, checkout our Microservice Reference Architecture Application [here](https://github.com/fabiogomezdiaz/refarch-cloudnative-kubernetes/tree/master).

## Contributing
If you would like to contribute to this repository, please fork it, submit a PR, and assign as reviewers any of the GitHub users listed here:
* https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-auth/graphs/contributors

### GOTCHAs
1. We use [Travis CI](https://travis-ci.org/) for our CI/CD needs, so when you open a Pull Request you will trigger a build in Travis CI, which needs to pass before we consider merging the PR. We use Travis CI to test the following:
    * Deploy the Customer service.
    * Building and running the Auth app against the Customer service and run API tests.
    * Build and Deploy a Docker Container, using the same Customer service.
    * Run API tests against the Docker Container.
    * Deploy a minikube cluster to test Helm charts.
    * Download Helm Chart dependencies and package the Helm chart.
    * Deploy the Helm Chart into Minikube.
    * Run API tests against the Helm Chart.

### Contributing a New Chart Package to Microservices Reference Architecture Helm Repository
To contribute a new chart version to the [Microservices Reference Architecture](https://github.com/fabiogomezdiaz/refarch-cloudnative-kubernetes/tree/master) helm repository, follow its guide here:
* https://github.com/fabiogomezdiaz/refarch-cloudnative-kubernetes/tree/master#contributing-a-new-chart-to-the-helm-repositories