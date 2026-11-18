package br.com.scriptping.accesspoint;

/** Compatibility for the existing encryption key and older callers. */
public class AccessPointSecrets extends br.com.scriptping.hosts.HostSecrets {
    public AccessPointSecrets(String path) { super(path); }
}
