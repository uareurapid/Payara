/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.security.services.provider.authorization;

import java.net.URI;
import java.security.Permission;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.hk2.api.ServiceLocator;

import org.glassfish.security.services.api.authorization.*;
import org.glassfish.security.services.api.authorization.AzResult.Decision;
import org.glassfish.security.services.api.authorization.AzResult.Status;
import org.glassfish.security.services.api.authorization.AuthorizationService.PolicyDeploymentContext;

import org.glassfish.security.services.config.SecurityProvider;
import org.glassfish.security.services.spi.AuthorizationProvider;
import org.glassfish.security.services.impl.authorization.AzResultImpl;
import org.glassfish.security.services.impl.authorization.AzObligationsImpl;

import org.glassfish.hk2.api.PerLookup;

import org.jvnet.hk2.annotations.Service;

import com.sun.logging.LogDomains;
import java.util.ArrayList;
import org.glassfish.security.services.api.authorization.AuthorizationAdminConstants;

import javax.security.auth.Subject;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.internal.api.KernelIdentity;
import org.glassfish.security.services.impl.NucleusKernelIdentity;


@Service (name="simpleAuthorization")
@PerLookup
public class SimpleAuthorizationProviderImpl implements AuthorizationProvider, PostConstruct {

    
    private AuthorizationProviderConfig cfg; 
    private boolean deployable;
    private String version;
    
    @Inject
    private ServerEnvironment serverEnv;
    
    @Inject
    private ServiceLocator serviceLocator;
    
    @Inject
    private KernelIdentity kernelIdentity;
    
    protected static final Logger _logger = 
        LogDomains.getLogger(SimpleAuthorizationProviderImpl.class, LogDomains.SECURITY_LOGGER);

    private final Decider decider = new Decider();
    
    @Override
    public void initialize(SecurityProvider providerConfig) {
                
        cfg = (AuthorizationProviderConfig)providerConfig.getSecurityProviderConfig().get(0);        
        deployable = cfg.getSupportPolicyDeploy();
        version = cfg.getVersion();
        if (_logger.isLoggable(Level.FINE)) {
            _logger.log(Level.FINE, "provide to do policy deploy: " + deployable);
            _logger.log(Level.FINE, "provide version to use: " + version);
        }
    }

    @Override
    public void postConstruct() {
        loadKernelIdentity();
    }
    
    /**
     * 
     */
    private void loadKernelIdentity() {
        /*
         * For testing, hk2 might not have been able to find the kernel identity
         * to use.  If so, use the open-source kernel identity.
         */
        if (kernelIdentity == null) {
            kernelIdentity = new NucleusKernelIdentity();
        }
    }


    @Override
    public AzResult getAuthorizationDecision(
        AzSubject subject,
        AzResource resource,
        AzAction action,
        AzEnvironment environment,
        List<AzAttributeResolver> attributeResolvers ) {

        //TODO: get user roles from Rolemapper, and do the policy  evaluation
        if ( ! isAdminResource(resource)) {
            /*
             * Log a loud warning if the resource name lacks the correct
             * scheme, but go ahead anyway and make the authorization decision.
             */
            final String resourceName = resource.getUri() == null ? "null" : resource.getUri().toASCIIString();
            _logger.log(Level.WARNING, resourceName, new IllegalArgumentException(resourceName));
        }
        return getAdminDecision(subject, resource, action, environment);
    }
    
    private boolean isAdminResource(final AzResource resource) {
        final URI resourceURI = resource.getUri();
        return "admin".equals(resourceURI.getScheme());
    }
    
    private AzResult getAdminDecision(
            final AzSubject subject,
            final AzResource resource,
            final AzAction action,
            final AzEnvironment environment) {
        AzResult rtn = new AzResultImpl(decider.decide(subject, resource, action, environment), 
                Status.OK, new AzObligationsImpl());
        
        return rtn;
    }

    @Override
    public PolicyDeploymentContext findOrCreateDeploymentContext(String appContext) {

        return null;
    }
    
    /**
     * Chooses what authorization decision to render.
     * 
     * We always require that the user be an administrator, established 
     * (for open-source) by having a Principal with name asadmin.
     * 
     * Beyond that, there are historical requirements for authenticated admin access:
     *  
     * - "External" users (CLI, browser, JMX)
     *   - can perform all actions locally on the DAS
     *   - can perform all actions remotely on the DAS if secure admin has been enabled [1]
     *   - JMX users can perform read-only actions on a non-DAS instance,
     *     remotely if secure admin has been enabled and always locally
     * 
     * - Selected local commands can act locally on the local DAS or local instance
     *   using the local password mechanism (stop-local-instance, for example)
     * 
     * - A server in the same domain can perform all actions in a local or remote server
     * 
     * - A client (typically run in a shell created by the DAS) can perform all actions 
     *   on a local or remote DAS if it uses the admin token mechanism to authenticate
     * 
     * [1] Note that any attempted remote access that is not permitted has 
     * already been rejected during authentication.
     * 
     * For enforcing read-only access we assume that any action other than the literal "read"
     * makes some change in the system.
     */
    private class Decider {
        
        private Decision decide(final AzSubject subject, final AzResource resource,
                final AzAction action, final AzEnvironment env) {
            /*
             * Basically, if the subject has one of the "special" principals
             * (token, local password, etc.) then we accept it for any action
             * on the DAS and on instances.  Otherwise, it's a person and
             * we allow full access on the DAS but read-only on instances.
             */
            Decision result = 
                    isSubjectKernelIdentity(subject.getSubject())
                    
                    || isSubjectTrustedForDASAndInstances(subject)
                   
                    || // Looks external.  Allow full access on DAS, read-only on instance.
                   
                    (isSubjectAnAdministrator(subject)
                    && ( serverEnv.isDas()
                        || isActionRead(action)
                       )
                   ) ? Decision.PERMIT : Decision.DENY;
            
            return result;
        }
        
        private boolean isSubjectKernelIdentity(final Subject s) {
            return ! kernelIdentity.getSubject().getPrincipals(KernelIdentity.KernelPrincipal.class).isEmpty();
        }
        
        private boolean isSubjectTrustedForDASAndInstances(final AzSubject subject) {
            final Set<String> principalNames = new HashSet<String>();
            for (Principal p : subject.getSubject().getPrincipals()) {
                principalNames.add(p.getName());
            }
            principalNames.retainAll(AuthorizationAdminConstants.TRUSTED_FOR_DAS_OR_INSTANCE);
            return ! principalNames.isEmpty();
        }
        
        private boolean isActionRead(final AzAction action) {
            return "read".equals(action.getAction());
        }

        private boolean isSubjectAnAdministrator(final AzSubject subject) {
            return isPrincipalType(subject, AuthorizationAdminConstants.ADMIN_GROUP);
        }

        private boolean isPrincipalType(final AzSubject subject, final String type) {
            for (Principal p : subject.getSubject().getPrincipals()) {
                if (type.equals(p.getName())) {
                    return true;
                }
            }
            return false;
        }
    }
}
