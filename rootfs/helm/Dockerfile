FROM lachlanevenson/k8s-helm:latest
LABEL authors="Marat Garafutdinov<maratoid@gmail.com>"

ENV KUBE_LATEST_VERSION="v1.9.5"

RUN apk add --update ca-certificates \
 && apk add --update -t deps curl \
 && apk add curl \
 && apk add git \
 && curl -L https://storage.googleapis.com/kubernetes-release/release/${KUBE_LATEST_VERSION}/bin/linux/amd64/kubectl -o /usr/local/bin/kubectl \
 && chmod +x /usr/local/bin/kubectl \
 && apk del --purge deps \
 && rm /var/cache/apk/*