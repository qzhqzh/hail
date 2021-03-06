.PHONY: build clean

ifeq ($(IN_HAIL_CI),1)
.PHONY: push deploy

PROJECT = $(shell gcloud config get-value project)
ROUTER_LATEST = gcr.io/$(PROJECT)/router:latest
ROUTER_IMAGE = gcr.io/$(PROJECT)/router:$(shell docker images -q --no-trunc router | sed -e 's,[^:]*:,,')
DOMAIN ?= hail.is

build:
	docker pull ubuntu:18.04
	-docker pull $(ROUTER_LATEST)
	docker build -t router --cache-from router,$(ROUTER_LATEST),ubuntu:18.04 .

push: build
	docker tag router $(ROUTER_LATEST)
	docker push $(ROUTER_LATEST)
	docker tag router $(ROUTER_IMAGE)
	docker push $(ROUTER_IMAGE)

deploy: push
	python3 ../ci2/jinja2_render.py '{"code":{"sha":"$(shell git rev-parse --short=12 HEAD)"},"deploy":true,"router_image":{"image":"$(ROUTER_IMAGE)"},"default_ns":{"name":"default","domain":"hail.is"}}' deployment.yaml deployment.yaml.out
	kubectl -n default apply -f deployment.yaml.out
else
build:
	docker build -t router .
endif

clean:
	rm -rf deployment.yaml
