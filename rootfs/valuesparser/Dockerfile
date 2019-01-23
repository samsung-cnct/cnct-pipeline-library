FROM python:3.6.7-alpine3.8
LABEL authors="Marat Garafutdinov<maratoid@gmail.com>"

COPY . /usr/local/bin

WORKDIR /usr/local/bin

RUN apk add --no-cache git \
  && pip install --no-cache-dir -r requirements.txt \
  && chmod +x ./parse.py \
  && rm requirements.txt

ENTRYPOINT [ "parse.py" ]