package com.alexrnv.httpbroadcast.upstream

import com.alexrnv.httpbroadcast.common.HttpCode
import com.alexrnv.httpbroadcast.downstream.EventHandler
import com.alexrnv.httpbroadcast.downstream.EventPolicy
import groovy.util.logging.Log
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.groovy.core.Context
import io.vertx.groovy.core.MultiMap
import io.vertx.groovy.core.http.HttpClient
import io.vertx.groovy.core.http.HttpClientRequest
import io.vertx.groovy.core.http.HttpServer
import io.vertx.groovy.core.http.HttpServerRequest
import io.vertx.lang.groovy.GroovyVerticle
/**
 * Main application verticle. Receives http PUT and POST requests and redirects them to
 * several downstream systems, gathers replies and sends aggregated response back.
 * Created: 8/21/15 7:13 PM
 * Author: alex
 */
@Log
class HttpDistributionServer extends GroovyVerticle {

    private final String[] ignoreHeaders = ["Host"]
    private final HttpMethod[] supportedMethods = [HttpMethod.POST, HttpMethod.PUT]

    private volatile HttpServer upstreamServer
    private volatile HttpClient downstreamClient

    @Override
    void start() throws Exception {

        Context context = vertx.getOrCreateContext()
        JsonObject config = context.config()

        log.info "Starting with config: $config"

        JsonObject upstream = config.getJsonObject("upstream")
        List<JsonObject> downstreams = config.getJsonArray("downstreams").asList()
        EventPolicy policy = EventPolicy.create(config.getString("eventPolicy"))
                .withDownstreams(downstreams.size())

        downstreamClient = vertx.createHttpClient()
        upstreamServer = vertx.createHttpServer().requestHandler { HttpServerRequest upstreamRequest ->

            HttpMethod method = upstreamRequest.method()
            if(!supportedMethods.contains(method)) {
                log.severe "Unsupported method $method, only " + Arrays.toString(supportedMethods) + " supported"
                upstreamRequest.response().setStatusCode(HttpCode.HTTP_CODE_ERR).end()
                return
            }

            EventHandler downstreamEventHandler = policy
                    .withServerResponse(upstreamRequest.response())
                    .handler()

            HttpClientRequest[] downstreamRequests = downstreams.collect { dst ->
                int port = dst.getInteger("port")
                String host = dst.getString("host")
                String uri = resolveUri(dst, upstreamRequest)
                log.info "Preparing request to $host:$port$uri"

                downstreamClient.post(port, host, uri, { resp ->
                    downstreamEventHandler.onDownstreamResponse(resp)
                })
                .setChunked(true)
            }

            copyHeaders(upstreamRequest, downstreamRequests)
            setRedirects(upstreamRequest, downstreamRequests, downstreamEventHandler)
        }
        .listen(upstream.getInteger("port"), upstream.getString("host"), { r ->
            if(r.succeeded()) {
                log.info "Started successfully"
            } else {
                log.severe("Failed to start" , r.cause())
            }
        })
    }

    void stop() {
        upstreamServer.close({ r ->
            if(r.succeeded()) {
                log.info "Upstream server stopped successfully"
            } else {
                log.severe("Upstream server stopped with errors" , r.cause())
            }
        })
        downstreamClient.close()
    }

    private def copyHeaders(upstreamRequest, downstreamRequests) {
        MultiMap headers = upstreamRequest.headers()
        headers.names().each { k ->
            if(!ignoreHeaders.contains(k)) {
                headers.getAll(k).each { v ->
                    log.info "Copy header $k:$v"
                    downstreamRequests.each { dstReq ->
                        dstReq.putHeader(k, v)
                    }
                }
            }
        }
    }

    private def setRedirects(upstreamRequest, downstreamRequests, downstreamEventHandler) {
        upstreamRequest.handler { buf ->
            downstreamRequests.each { dstReq ->
                dstReq.write(buf)
            }
        }

        upstreamRequest.endHandler { v ->
            downstreamRequests.each { dstReq ->
                dstReq.end()
                downstreamEventHandler.onDownstreamRequest(dstReq)
            }
        }
    }

    private def resolveUri = { JsonObject dst, HttpServerRequest upstreamRequest ->
        String pathUri = upstreamRequest.path()
        JsonArray uriMappingsArr = dst.getJsonArray("uriMappings")
        if(uriMappingsArr == null)
            return pathUri

        List<JsonObject> uriMappings = uriMappingsArr.asList()

        String newUri = null
        uriMappings.each { m ->
            if(pathUri.equals(m.getString("from"))) {
                newUri = m.getString("to")
                log.info "Redirecting uri from $pathUri to $newUri"
            }
        }
        newUri = newUri ?: pathUri
        String query = upstreamRequest.query()
        query != null ? "$newUri?$query" : "$newUri"
    }
}
