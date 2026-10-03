/**
 * Local control of Midea air conditioners (device type 0xAC), ported from the midea-local library
 * that Home Assistant's midea integration is built on.
 *
 * <p>Start with {@link de.mirranet.midea.ac.MideaAirConditioner}. You need a
 * {@link de.mirranet.midea.ac.DeviceConfig} with the unit's address and id, and for V3 units a
 * token and key. Getting those is a one-time job for the {@code discovery} and {@code cloud}
 * packages.
 */
package de.mirranet.midea.ac;
