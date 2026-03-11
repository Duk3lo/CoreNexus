package org.astral.core.config.nexus;

public class HealingConfig {
    public boolean enable = true;

    // Tiempos dinámicos: D (Días), H (Horas), M (Minutos), S (Segundos)
    public String initial_delay = "30S";
    public String check_interval = "1M";
    public String scheduled_restart = "2D";

    // Configuración de TPS
    public double min_tps_threshold = 15.0;
    public int max_strikes = 3;

    public HealingConfig() {}
}