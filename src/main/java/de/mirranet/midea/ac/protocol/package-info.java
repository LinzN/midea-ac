/**
 * The wire format. You only need this to build your own tooling or to debug a unit that behaves
 * oddly; the client in the parent package covers normal use.
 *
 * <p>From the outside in: an optional 0x8370 envelope (V3 only, {@link LocalSecurity}), the 0x5A5A
 * packet ({@link Packet}), and the 0xAA frame ({@link Frame}) with the actual message, built by
 * {@link AcMessages}.
 */
package de.mirranet.midea.ac.protocol;
