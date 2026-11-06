/**
 * Control of the apps' own consumers (ADR 0015, orch.md decisions 66–68): dead-letter topics, automatic pause while
 * a dependency is down, and pause, resume and connection checks by hand, with the status and the commands on Pulsar
 * topics.
 */
package com.catacumbas69.catacommons.consumers;
