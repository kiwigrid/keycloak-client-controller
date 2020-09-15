package com.kiwigrid.keycloak.controller;

import java.util.HashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.micronaut.context.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class KubernetesController<T extends CustomResource> implements Watcher<T> {

	protected final Logger log = LoggerFactory.getLogger(getClass());
	protected final KubernetesClient kubernetes;
	protected final Map<String, T> resources = new HashMap<>();
	protected final MixedOperation<T, ? extends CustomResourceList<T>, ?, ?> customResources;

	@Value("${controller.namespaced:true}")
	protected boolean namespaced;

	protected KubernetesController(KubernetesClient kubernetes,
			CustomResourceDefinition crd,
			Class<T> type,
			Class<? extends CustomResourceList<T>> listType,
			Class<? extends CustomResourceDoneable<T>> doneableType) {
		this.kubernetes = kubernetes;
		this.customResources = kubernetes.customResources(crd, type, listType, doneableType);
		KubernetesDeserializer.registerCustomKind(
				crd.getSpec().getGroup() + "/" + crd.getSpec().getVersion(),
				crd.getSpec().getNames().getKind(), type);
	}

	public abstract void apply(T resource);

	public abstract void delete(T resource);

	public abstract void retry();

	// watcher methods

	public Watch watch() {
		log.trace("Start watcher.");
		if(namespaced) {
			return customResources.watch(this);
		}
		return customResources.inAnyNamespace().watch(this);
	}

	@Override
	public void eventReceived(Action action, T resource) {

		var id = resource.getMetadata().getNamespace() + "/" + resource.getMetadata().getName();
		log.trace("Received event {} for {}.", action, id);

		try {
			if (action == Action.DELETED) {
				delete(resource);
				resources.remove(id);
			} else {
				if (resources.containsKey(id) && resources.get(id).equals(resource)) {
					log.trace("Resource {} did not change, ignore!", id);
					return;
				}
				apply(resource);
				resources.put(id, resource);
			}
		} catch (RuntimeException e) {
			log.error("Failed to " + action + " resource " + id + ".", e);
		}
	}

	@Override
	public void onClose(KubernetesClientException cause) {
		if (cause != null) {
			log.error("Unexpectedly closed watcher.", cause);
		}
	}
}