package net.ripe.db.whois.api.rest;

import com.google.common.collect.Lists;
import net.ripe.db.whois.api.rest.client.StreamingException;
import net.ripe.db.whois.api.rest.domain.Link;
import net.ripe.db.whois.api.rest.domain.Parameters;
import net.ripe.db.whois.api.rest.domain.Service;
import net.ripe.db.whois.api.rest.domain.WhoisObject;
import net.ripe.db.whois.api.rest.domain.WhoisResources;
import net.ripe.db.whois.api.rest.mapper.AttributeMapper;
import net.ripe.db.whois.api.rest.mapper.WhoisObjectServerMapper;
import net.ripe.db.whois.common.Message;
import net.ripe.db.whois.common.Messages;
import net.ripe.db.whois.common.domain.ResponseObject;
import net.ripe.db.whois.common.rpsl.RpslObject;
import net.ripe.db.whois.query.domain.MessageObject;
import net.ripe.db.whois.query.domain.TagResponseObject;
import net.ripe.db.whois.query.handler.QueryHandler;
import net.ripe.db.whois.query.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static net.ripe.db.whois.api.rest.RestServiceHelper.getServerAttributeMapper;

@Component
public class RpslObjectStreamer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RpslObjectStreamer.class);

    private final QueryHandler queryHandler;
    private final WhoisObjectServerMapper whoisObjectServerMapper;

    @Autowired
    public RpslObjectStreamer(
            final QueryHandler queryHandler,
            final WhoisObjectServerMapper whoisObjectServerMapper) {
        this.queryHandler = queryHandler;
        this.whoisObjectServerMapper = whoisObjectServerMapper;
    }

    public Response handleQueryAndStreamResponse(final Query query,
                                                  final HttpServletRequest request,
                                                  final InetAddress remoteAddress,
                                                  @Nullable final Parameters parameters,
                                                  @Nullable final Service service,
                                                  final boolean unformatted) {
        return Response.ok(new Streamer(request, query, remoteAddress, parameters, service, unformatted)).build();
    }

    private class Streamer implements StreamingOutput {

        private final HttpServletRequest request;
        private final Query query;
        private final InetAddress remoteAddress;
        private final Parameters parameters;
        private final Service service;
        private StreamingMarshal streamingMarshal;
        private Class<? extends AttributeMapper> attributeMapper;

        public Streamer(
                final HttpServletRequest request,
                final Query query,
                final InetAddress remoteAddress,
                final Parameters parameters,
                final Service service,
                final boolean unformatted) {
            this.request = request;
            this.query = query;
            this.remoteAddress = remoteAddress;
            this.parameters = parameters;
            this.service = service;
            this.attributeMapper = getServerAttributeMapper(unformatted);
        }

        @Override
        public void write(final OutputStream output) throws IOException, WebApplicationException {
            streamingMarshal = StreamingHelper.getStreamingMarshal(request, output);

            final SearchResponseHandler responseHandler = new SearchResponseHandler();
            try {
                final int contextId = System.identityHashCode(Thread.currentThread());
                queryHandler.streamResults(query, remoteAddress, contextId, responseHandler);

                if (!responseHandler.rpslObjectFound()) {
                    throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                            .entity(RestServiceHelper.createErrorEntity(request, responseHandler.flushAndGetErrors()))
                            .build());
                }
                responseHandler.flushAndGetErrors();

            } catch (StreamingException ignored) {
                LOGGER.debug("{}: {}", ignored.getClass().getName(), ignored.getMessage());
            } catch (RuntimeException e) {
                throw createWebApplicationException(e, responseHandler);
            }
        }

        private WebApplicationException createWebApplicationException(final RuntimeException exception, final SearchResponseHandler responseHandler) {
            if (exception instanceof WebApplicationException) {
                return (WebApplicationException) exception;
            } else {
                return RestServiceHelper.createWebApplicationException(exception, request, responseHandler.flushAndGetErrors());
            }
        }

        private class SearchResponseHandler extends ApiResponseHandler {
            private boolean rpslObjectFound;

            // tags come separately
            private final Queue<RpslObject> rpslObjectQueue = new ArrayDeque<>(1);
            private TagResponseObject tagResponseObject = null;
            private final List<Message> errors = Lists.newArrayList();

            // TODO: [AH] replace this 'if instanceof' mess with an OO approach
            @Override
            public void handle(final ResponseObject responseObject) {
                if (responseObject instanceof TagResponseObject) {
                    tagResponseObject = (TagResponseObject) responseObject;
                } else if (responseObject instanceof RpslObject) {
                    streamRpslObject((RpslObject) responseObject);
                } else if (responseObject instanceof MessageObject) {
                    final Message message = ((MessageObject) responseObject).getMessage();
                    if (message != null && Messages.Type.INFO != message.getType()) {
                        errors.add(message);
                    }
                }
            }

            private void streamRpslObject(final RpslObject rpslObject) {
                if (!rpslObjectFound) {
                    rpslObjectFound = true;
                    startStreaming();
                }
                streamObject(rpslObjectQueue.poll());
                rpslObjectQueue.add(rpslObject);
            }

            private void startStreaming() {
                streamingMarshal.open();

                if (service != null) {
                    streamingMarshal.write("service", service);
                }

                if (parameters != null) {
                    streamingMarshal.write("parameters", parameters);
                }

                streamingMarshal.start("objects");
                streamingMarshal.startArray("object");
            }

            private void streamObject(@Nullable final RpslObject rpslObject) {
                if (rpslObject == null) {
                    return;
                }

                final WhoisObject whoisObject = whoisObjectServerMapper.map(rpslObject, tagResponseObject, attributeMapper);

                streamingMarshal.writeArray(whoisObject);
                tagResponseObject = null;
            }

            public boolean rpslObjectFound() {
                return rpslObjectFound;
            }

            public List<Message> flushAndGetErrors() {
                if (!rpslObjectFound) {
                    return errors;
                }
                streamObject(rpslObjectQueue.poll());

                streamingMarshal.endArray();

                streamingMarshal.end("objects");
                if (errors.size() > 0) {
                    streamingMarshal.write("errormessages", RestServiceHelper.createErrorMessages(errors));
                    errors.clear();
                }

                streamingMarshal.write("terms-and-conditions", Link.create(WhoisResources.TERMS_AND_CONDITIONS));
                streamingMarshal.end("whois-resources");
                streamingMarshal.close();
                return errors;
            }
        }

    }

}