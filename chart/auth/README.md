# refarch-cloudnative-micro-auth: Secure REST API with OAuth 2.0 and Authorization Service

## Introduction
This chart will deploy a Spring Boot Application with a Customer database onto a Kubernetes Cluster.

![Application Architecture](https://github.com/fabiogomezdiaz/refarch-cloudnative-auth/raw/master/static/diagrams/auth.png?raw=true)

Here is an overview of the chart's features:
- Leverage [`Spring Boot`](https://projects.spring.io/spring-boot/) framework to build a Microservices application.
- Spring-based [`Authorization Server`](https://spring.io/projects/spring-security-oauth) application that handles user authentication and authorization.
- Uses [`Spring Feign Client`](https://cloud.spring.io/spring-cloud-netflix/multi/multi_spring-cloud-feign.html) to call Customer Microservice to validate login credentials.
- Return a signed [JWT](https://jwt.io) Bearer token back to caller for identity propagation and authorization
- Uses [`Docker`](https://docs.docker.com/) to package application binary and its dependencies.
- Uses [`Helm`](https://helm.sh/) to package application and Customer deployment configuration and deploy to a [`Kubernetes`](https://kubernetes.io/) cluster.

## Chart Source
The source for the `Auth` chart can be found at:
* https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-auth/tree/master/chart/auth

The source for the `Customer` chart can be found at:
* https://github.com/fabiogomezdiaz/refarch-cloudnative-micro-customer/tree/master/chart/customer

## APIs
- GET `/oauth/authorize`
- POST `/oauth/token`

## Deploy Auth Application to Kubernetes Cluster from CLI
To deploy the Auth Chart and its Customer dependency Chart to a Kubernetes cluster using Helm CLI, follow the instructions below:
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

# Clone auth repository:
git clone http://github.com/refarch-cloudnative-micro-auth.git

# Go to Chart Directory
cd refarch-cloudnative-micro-auth/chart/auth

# Deploy Auth to Kubernetes cluster
helm upgrade --install auth --set service.type=NodePort,customer.url=http://customer-customer:8082 .
```