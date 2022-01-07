/**
 *  Copyright 2121 Martynas Jusevičius <martynas@atomgraph.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.atomgraph.linkeddatahub.resource.graph;

import com.atomgraph.client.vocabulary.AC;
import com.atomgraph.core.MediaTypes;
import com.atomgraph.core.client.LinkedDataClient;
import com.atomgraph.core.model.EndpointAccessor;
import com.atomgraph.linkeddatahub.model.Service;
import com.atomgraph.linkeddatahub.server.model.Patchable;
import com.atomgraph.linkeddatahub.server.model.impl.GraphStoreImpl;
import com.atomgraph.linkeddatahub.vocabulary.LAPP;
import com.atomgraph.linkeddatahub.vocabulary.VoID;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Providers;
import org.apache.jena.ontology.Ontology;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.update.UpdateRequest;
import org.apache.jena.vocabulary.RDF;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;

/**
 *
 * @author {@literal Martynas Jusevičius <martynas@atomgraph.com>}
 */
public class Item extends GraphStoreImpl implements Patchable
{

    private final URI uri;
    private final EndpointAccessor endpointAccessor;
    
    @Inject
    public Item(@Context Request request, @Context UriInfo uriInfo, MediaTypes mediaTypes,
        Optional<Ontology> ontology, Optional<Service> service, @Context Providers providers, com.atomgraph.linkeddatahub.Application system)
    {
        super(request, uriInfo, mediaTypes, ontology, service, providers, system);
        this.uri = uriInfo.getAbsolutePath();
        this.endpointAccessor = service.get().getEndpointAccessor();
    }

    @Override
    @GET
    public Response get(@QueryParam("default") @DefaultValue("false") Boolean defaultGraph, @QueryParam("graph") URI graphUri)
    {
        // if there are datasets in the system context model what match the request URI, return data from their proxied URI
        Resource dataset = getLongestURIDataset(getLengthMap(getRelativeDatasets(getSystem().getContextModel(), VoID.Dataset, getURI())));
        if (dataset != null)
        {
            Resource proxy = dataset.getPropertyResourceValue(LAPP.proxy);
            if (proxy == null) throw new InternalServerErrorException(new IllegalStateException("Dataset resource <" + dataset.getURI() + "> has no lapp:prefix value"));
            
            URI proxiedURI = getProxiedURI(URI.create(proxy.getURI()), getURI());
            return getResponse(LinkedDataClient.create(getSystem().getClient().target(proxiedURI), getMediaTypes()).get(), getURI());
        }
        
        return super.get(false, getURI());
    }
    
    @Override
    @POST
    public Response post(Model model, @QueryParam("default") @DefaultValue("false") Boolean defaultGraph, @QueryParam("graph") URI graphUri)
    {
        return super.post(model, false, getURI());
    }
    
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Override
    public Response postMultipart(FormDataMultiPart multiPart, @QueryParam("default") @DefaultValue("false") Boolean defaultGraph, @QueryParam("graph") URI graphUri)
    {
        return super.postMultipart(multiPart, false, getURI());
    }
    
    @Override
    @PUT
    public Response put(Model model, @QueryParam("default") @DefaultValue("false") Boolean defaultGraph, @QueryParam("graph") URI graphUri)
    {
        return super.put(model, false, getURI());
    }
    
    @PUT
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Override
    public Response putMultipart(FormDataMultiPart multiPart, @QueryParam("default") @DefaultValue("false") Boolean defaultGraph, @QueryParam("graph") URI graphUri)
    {
        return super.putMultipart(multiPart, false, getURI());
    }
    
    @Override
    @DELETE
    public Response delete(@QueryParam("default") @DefaultValue("false") Boolean defaultGraph, @QueryParam("graph") URI graphUri)
    {
        return super.delete(false, getURI());
    }
    
    @PATCH
    @Override
    public Response patch(UpdateRequest updateRequest)
    {
        // TO-DO: do a check that the update only uses this named graph
        getEndpointAccessor().update(updateRequest, Collections.<URI>emptyList(), Collections.<URI>emptyList());
        
        return Response.ok().build();
    }
    
    /**
     * Gets a list of media types that a writable for a message body class.
     * 
     * @param clazz message body class, normally <code>Dataset.class</code> or <code>Model.class</code>
     * @return list of media types
     */
    @Override
    public List<MediaType> getWritableMediaTypes(Class clazz)
    {
        // restrict writable MediaTypes to the requested one (usually by RDF export feature)
        if (getUriInfo().getQueryParameters().containsKey(AC.accept.getLocalName())) // TO-DO: move to ResourceFilter?
        {
            String accept = getUriInfo().getQueryParameters().getFirst(AC.accept.getLocalName());
            
            MediaType mediaType = MediaType.valueOf(accept).withCharset(StandardCharsets.UTF_8.name()); // set charset=UTF-8
            return Arrays.asList(mediaType);
        }

        return super.getWritableMediaTypes(clazz);
    }
    
    public Resource getLongestURIDataset(Map<Integer, Resource> lengthMap)
    {
        // select the app with the longest URI match, as the model contains a pair of EndUserApplication/AdminApplication
        TreeMap<Integer, Resource> datasets = new TreeMap(lengthMap);
        if (!datasets.isEmpty()) return datasets.lastEntry().getValue();
        
        return null;
    }

    public Map<URI, Resource> getRelativeDatasets(Model model, Resource type, URI absolutePath)
    {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");
        if (type == null) throw new IllegalArgumentException("Resource cannot be null");
        if (absolutePath == null) throw new IllegalArgumentException("URI cannot be null");

        Map<URI, Resource> datasets = new HashMap<>();
        
        ResIterator it = model.listSubjectsWithProperty(RDF.type, type);
        try
        {
            while (it.hasNext())
            {
                Resource dataset = it.next();
                
                if (!dataset.hasProperty(LAPP.prefix))
                    throw new InternalServerErrorException(new IllegalStateException("Dataset resource <" + dataset.getURI() + "> has no lapp:prefix value"));
                
                URI prefix = URI.create(dataset.getPropertyResourceValue(LAPP.prefix).getURI());
                URI relative = prefix.relativize(absolutePath);
                if (!relative.isAbsolute() && !relative.toString().equals("")) datasets.put(prefix, dataset);
            }
        }
        finally
        {
            it.close();
        }

        return datasets;
    }
    
    public Map<Integer, Resource> getLengthMap(Map<URI, Resource> apps)
    {
        if (apps == null) throw new IllegalArgumentException("Map cannot be null");

        Map<Integer, Resource> lengthMap = new HashMap<>();
        
        Iterator<Map.Entry<URI, Resource>> it = apps.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<URI, Resource> entry = it.next();
            lengthMap.put(entry.getKey().toString().length(), entry.getValue());
        }
        
        return lengthMap;
    }
    
    public URI getProxiedURI(URI proxy, URI uri)
    {
        return UriBuilder.fromUri(uri).
            scheme(proxy.getScheme()).
            host(proxy.getHost()).
            port(proxy.getPort()).
            build();
    }
    
    public URI getURI()
    {
        return uri;
    }
    
    public EndpointAccessor getEndpointAccessor()
    {
        return endpointAccessor;
    }
    
}
