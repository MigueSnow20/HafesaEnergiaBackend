# Integración de la V1 — 2026-10-01

Fuente: `C:/Users/migue/Desktop/PetroXpert/PetroXpertBackend`, no `_reference`.
Destino observado: `MigueSnow20/HafesaEnergiaBackend`, rama `main`, HEAD `3724fb6`.
Se conservaron los scripts de diagnóstico locales y las credenciales existentes, sin copiarlas ni publicarlas.

## Despliegue conservado

`fly.toml` permanece intacto: app `petroxpertbackend`, región `cdg`, puerto público 3000,
una VM de 1 GB y la política existente de parada automática. No se ejecutó despliegue.

Express conserva PostgreSQL y todos sus endpoints legacy. `/api/v1/**` se reenvía al Spring interno
en loopback:8080. Spring usa perfil `v1`, sin DB propia, y consulta el legacy en loopback:3000.
No se realiza una llamada circular al hostname publicado. `Dockerfile` mantiene Playwright 1.61.1
y añade Java 21/Maven multimódulo; `npm start` supervisa ambos procesos y detiene el conjunto si uno falla.
El frontend se publica por separado en Vercel; este contenedor no compila ni sirve su bundle.

## Intervalos y fuentes

`MARKET_REFRESH=30s` es la configuración de adquisición Spring y polling Vue.
El collector usa un hilo y espera tras completar cada adquisición; el backoff puede aumentar la espera.
Express serializa el acceso al Browser compartido y conserva la deduplicación y la caché de 60 s.
`obtenidoEn` no se renueva al leer esa caché. No se garantizan precios nuevos cada 30 s.
`MARKET_TIMEOUT_MS` vale 8000 por defecto para navegación y localización de precio, igual al límite
de fuente de la V1. El supervisor permite al adaptador Spring esperar los tres mercados serializados
(seis operaciones como máximo más 5 s de margen). Se mantienen errores y último snapshot válido.

## Comprobaciones locales

```powershell
npm run check
npm test
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.11'
mvn -B -ntp package
```

Los tests de integración usan HTTP local, sin PostgreSQL, escrituras remotas ni scraping de producción.
No arrancar `server.js` para comprobar solo la compilación: conserva su inicialización SQL legacy al arrancar.
La sustitución de esa persistencia continúa pendiente de Flyway y no forma parte de esta integración.

## Antes de publicar

- Construir y probar la imagen Docker completa: Docker no está disponible en este equipo.
- Revisar uso de memoria real al ejecutar Node, Chromium y Java en la VM existente de 1 GB.
- Revisar que los secretos y ajustes remotos Fly no sobrescriban `MARKET_REFRESH=30s` ni los puertos.
- Conservar el mecanismo actual de inyección de PostgreSQL; no se copió ni cambió `database.env`.
- Publicar primero backend y comprobar `/api/v1/health`; después publicar el frontend en Vercel.
- La política actual de auto-stop interrumpe collectors cuando la VM está parada. Mantener extracción
  continua sin visitantes requeriría acordar otro ajuste de Fly; esta política no se cambia aquí.
- Los bloqueos de fuentes y de widgets no están resueltos por la integración.

Sin commit, push, cambios en Fly/Vercel ni migraciones DB.
