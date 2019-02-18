FROM golang:1.11-alpine3.8 as builder
LABEL authors="Marat Garafutdinov<maratoid@gmail.com>"

ENV KUBERNETES_VERSION=1.13.0
ENV KUBERNETES_ARCH=amd64

RUN apk update && \
  apk add curl git

RUN go get sigs.k8s.io/kind

RUN curl -L https://storage.googleapis.com/kubernetes-release/release/v${KUBERNETES_VERSION}/bin/linux/${KUBERNETES_ARCH}/kubectl \
  -o /usr/local/bin/kubectl && \
  chmod +x /usr/local/bin/kubectl

FROM docker:18.09.2
LABEL authors="Marat Garafutdinov<maratoid@gmail.com>"

RUN apk --no-cache add ca-certificates && mkdir -p /usr/local/bin
COPY --from=builder /usr/local/bin/kubectl /usr/local/bin/kubectl
COPY --from=builder /go/bin/kind /usr/local/bin/kind

CMD ["/usr/local/bin/kind"]  