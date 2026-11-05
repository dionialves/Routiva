package br.com.scriptping.zabbix;

import org.springframework.data.jpa.repository.JpaRepository;

interface ZabbixSettingsRepository extends JpaRepository<ZabbixSettingsEntity, Integer> {}
