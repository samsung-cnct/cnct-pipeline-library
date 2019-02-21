FROM golang:1.11-alpine3.8 as builder
LABEL authors="Marat Garafutdinov<maratoid@gmail.com>"

ARG KUBERNETES_VERSION=1.13.0
ARG KUBERNETES_ARCH=amd64
ARG HELM_VERSION=2.12.1
ARG HELM_ARCH=amd64
ARG HELMFILE_VER=0.45.3
ARG AWS_AUTH_VER="1.11.5/2018-12-06"
ARG AWS_AUTH_ARCH=amd64

RUN apk update && \
  apk add curl git

RUN go get sigs.k8s.io/kind

RUN curl -L https://storage.googleapis.com/kubernetes-release/release/v${KUBERNETES_VERSION}/bin/linux/${KUBERNETES_ARCH}/kubectl \
  -o /usr/local/bin/kubectl && \
  chmod +x /usr/local/bin/kubectl && \
  curl -L https://amazon-eks.s3-us-west-2.amazonaws.com/${AWS_AUTH_VER}/bin/linux/${AWS_AUTH_ARCH}/aws-iam-authenticator \
  -o /usr/local/bin/aws-iam-authenticator && \
  chmod +x /usr/local/bin/aws-iam-authenticator && \
  curl -L https://github.com/roboll/helmfile/releases/download/v${HELMFILE_VER}/helmfile_linux_amd64 \
  -o /usr/local/bin/helmfile && \
  chmod +x /usr/local/bin/helmfile && \
  curl -L https://storage.googleapis.com/kubernetes-helm/helm-v${HELM_VERSION}-linux-${HELM_ARCH}.tar.gz \
  -o helm-v${HELM_VERSION}-linux-${HELM_ARCH}.tar.gz && \
  tar -xvzf helm-v${HELM_VERSION}-linux-${HELM_ARCH}.tar.gz && \
  cp linux-${HELM_ARCH}/helm /usr/local/bin && \
  chmod +x /usr/local/bin/helm

FROM docker:18.09.2
LABEL authors="Marat Garafutdinov<maratoid@gmail.com>"

ARG CLI_VERSION=1.16.109

RUN mkdir -p /usr/local/bin && apk --update  –no-cache add curl git bash jq python py-pip groff less
COPY --from=builder /usr/local/bin/kubectl /usr/local/bin/kubectl
COPY --from=builder /usr/local/bin/aws-iam-authenticator /usr/local/bin/aws-iam-authenticator
COPY --from=builder /usr/local/bin/helmfile /usr/local/bin/helmfile
COPY --from=builder /usr/local/bin/helm /usr/local/bin/helm
COPY --from=builder /go/bin/kind /usr/local/bin/kind
RUN helm init --client-only && helm plugin install https://github.com/rimusz/helm-tiller && \
  pip install --upgrade pip && \
  pip install --no-cache-dir awscli==${CLI_VERSION}

CMD ["/usr/local/bin/kind"]  