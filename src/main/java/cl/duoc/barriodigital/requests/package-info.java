/**
 * Servicio que guarda los trámites en Oracle.
 * Los crea, los lista, cambia su estado y, después del commit, publica el evento en RabbitMQ.
 */
package cl.duoc.barriodigital.requests;
