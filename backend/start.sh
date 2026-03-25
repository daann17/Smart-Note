#!/usr/bin/env bash

set -a

if [ -f ".env" ]; then
  . ./.env
fi

set +a

if [ -x "./mvnw" ]; then
  ./mvnw spring-boot:run
else
  mvn spring-boot:run
fi
