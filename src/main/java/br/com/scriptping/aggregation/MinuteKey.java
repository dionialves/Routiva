package br.com.scriptping.aggregation;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

@Embeddable
record MinuteKey(
    @Embedded HostTarget endpoint,
    @Column(name = "minute_at") long minute
) implements Serializable {}
