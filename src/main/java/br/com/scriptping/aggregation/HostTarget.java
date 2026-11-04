package br.com.scriptping.aggregation;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
record HostTarget(@Column(name = "host_id") String host, String target) implements Serializable {}
