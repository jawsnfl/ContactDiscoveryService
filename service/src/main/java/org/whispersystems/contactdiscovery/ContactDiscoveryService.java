/**
 * Copyright (C) 2017 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.contactdiscovery;

import org.apache.commons.codec.DecoderException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.whispersystems.contactdiscovery.auth.SignalService;
import org.whispersystems.contactdiscovery.auth.SignalServiceAuthenticator;
import org.whispersystems.contactdiscovery.auth.User;
import org.whispersystems.contactdiscovery.auth.UserAuthenticator;
import org.whispersystems.contactdiscovery.client.IntelClient;
import org.whispersystems.contactdiscovery.directory.DirectoryManager;
import org.whispersystems.contactdiscovery.enclave.SgxEnclaveManager;
import org.whispersystems.contactdiscovery.enclave.SgxHandshakeManager;
import org.whispersystems.contactdiscovery.enclave.SgxRevocationListManager;
import org.whispersystems.contactdiscovery.limits.RateLimiter;
import org.whispersystems.contactdiscovery.mappers.NoSuchEnclaveExceptionMapper;
import org.whispersystems.contactdiscovery.mappers.RateLimitExceededExceptionMapper;
import org.whispersystems.contactdiscovery.mappers.SignedQuoteUnavailableExceptionMapper;
import org.whispersystems.contactdiscovery.providers.RedisClientFactory;
import org.whispersystems.contactdiscovery.requests.RequestManager;
import org.whispersystems.contactdiscovery.resources.ContactDiscoveryResource;
import org.whispersystems.contactdiscovery.resources.DirectoryManagementResource;
import org.whispersystems.contactdiscovery.resources.RemoteAttestationResource;
import org.whispersystems.contactdiscovery.util.NativeUtils;
import org.whispersystems.dropwizard.simpleauth.AuthDynamicFeature;
import org.whispersystems.dropwizard.simpleauth.AuthValueFactoryProvider;
import org.whispersystems.dropwizard.simpleauth.BasicCredentialAuthFilter;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyStoreException;
import java.security.Security;
import java.security.cert.CertificateException;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

/**
 * Main entry point for the service
 *
 * @author Moxie Marlinspike
 */
public class ContactDiscoveryService extends Application<ContactDiscoveryConfiguration> {

  public static void main(String[] args) throws Exception {
    new ContactDiscoveryService().run(args);
  }

  @Override
  public String getName() {
    return "contact-discovery-service";
  }

  @Override
  public void initialize(Bootstrap<ContactDiscoveryConfiguration> bootstrap) {}

  @Override
  public void run(ContactDiscoveryConfiguration configuration, Environment environment)
      throws CertificateException, KeyStoreException, IOException, DecoderException, URISyntaxException
  {
    NativeUtils.loadNativeResource("/enclave-jni.so");
    Security.addProvider(new BouncyCastleProvider());

    UserAuthenticator          userAuthenticator          = new UserAuthenticator(configuration.getSignalServiceConfiguration().getUserAuthenticationToken());
    SignalServiceAuthenticator signalServiceAuthenticator = new SignalServiceAuthenticator(configuration.getSignalServiceConfiguration().getServerAuthenticationToken());

    IntelClient intelClient = new IntelClient(configuration.getEnclaveConfiguration().getIasHost(),
                                              configuration.getEnclaveConfiguration().getCertificate(),
                                              configuration.getEnclaveConfiguration().getKey(),
                                              configuration.getEnclaveConfiguration().getAcceptGroupOutOfDate());

    RedisClientFactory       cacheClientFactory       = new RedisClientFactory(configuration.getRedisConfiguration().getUrl());
    SgxEnclaveManager        sgxEnclaveManager        = new SgxEnclaveManager(configuration.getEnclaveConfiguration());
    SgxRevocationListManager sgxRevocationListManager = new SgxRevocationListManager(sgxEnclaveManager, intelClient);
    SgxHandshakeManager      sgxHandshakeManager      = new SgxHandshakeManager(sgxEnclaveManager, sgxRevocationListManager, intelClient);
    DirectoryManager         directoryManager         = new DirectoryManager(cacheClientFactory, configuration.getDirectoryConfiguration().getInitialSize(), configuration.getDirectoryConfiguration().getLoadFactor());
    RequestManager           requestManager           = new RequestManager(directoryManager, sgxEnclaveManager, configuration.getEnclaveConfiguration().getTargetBatchSize());

    RateLimiter discoveryRateLimiter   = new RateLimiter(cacheClientFactory.getRedisClientPool(), "contactDiscovery", configuration.getLimitsConfiguration().getContactQueries().getBucketSize(), configuration.getLimitsConfiguration().getContactQueries().getLeakRatePerMinute()         );
    RateLimiter attestationRateLimiter = new RateLimiter(cacheClientFactory.getRedisClientPool(), "remoteAttestation", configuration.getLimitsConfiguration().getRemoteAttestations().getBucketSize(), configuration.getLimitsConfiguration().getRemoteAttestations().getLeakRatePerMinute());

    RemoteAttestationResource   remoteAttestationResource   = new RemoteAttestationResource(sgxHandshakeManager, attestationRateLimiter);
    ContactDiscoveryResource    contactDiscoveryResource    = new ContactDiscoveryResource(discoveryRateLimiter, requestManager);
    DirectoryManagementResource directoryManagementResource = new DirectoryManagementResource(directoryManager);

    environment.lifecycle().manage(sgxEnclaveManager);
    environment.lifecycle().manage(sgxRevocationListManager);
    environment.lifecycle().manage(sgxHandshakeManager);
    environment.lifecycle().manage(requestManager);
    environment.lifecycle().manage(directoryManager);

    environment.jersey().register(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>()
                                                             .setAuthenticator(userAuthenticator)
                                                             .setPrincipal(User.class)
                                                             .buildAuthFilter(),
                                                         new BasicCredentialAuthFilter.Builder<SignalService>()
                                                             .setAuthenticator(signalServiceAuthenticator)
                                                             .setPrincipal(SignalService.class)
                                                             .buildAuthFilter()));
    environment.jersey().register(new AuthValueFactoryProvider.Binder());

    environment.jersey().register(remoteAttestationResource);
    environment.jersey().register(contactDiscoveryResource);
    environment.jersey().register(directoryManagementResource);

    environment.jersey().register(new NoSuchEnclaveExceptionMapper());
    environment.jersey().register(new RateLimitExceededExceptionMapper());
    environment.jersey().register(new SignedQuoteUnavailableExceptionMapper());
  }

}
