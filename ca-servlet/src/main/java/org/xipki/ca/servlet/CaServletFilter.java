/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audits.Audits;
import org.xipki.ca.api.internal.CertPublisherFactoryRegisterImpl;
import org.xipki.ca.api.internal.CertprofileFactoryRegisterImpl;
import org.xipki.ca.api.profile.CertprofileFactory;
import org.xipki.ca.api.profile.CertprofileFactoryRegister;
import org.xipki.ca.certprofile.xml.internal.CertprofileFactoryImpl;
import org.xipki.ca.server.impl.CaManagerImpl;
import org.xipki.publisher.ocsp.OcspCertPublisherFactory;
import org.xipki.securities.Securities;
import org.xipki.util.InvalidConfException;
import org.xipki.util.IoUtil;

/**
 * TODO.
 * @author Lijun Liao
 */
public class CaServletFilter implements Filter {
  static final String ATTR_XIPKI_PATH = "xipki_path";

  private static final Logger LOG = LoggerFactory.getLogger(CaServletFilter.class);

  private static final String DFLT_CA_SERVER_CFG = "xipki/etc/ca/ca.properties";

  private Audits audits;

  private Securities securities;

  private CaManagerImpl caManager;

  private HealthCheckServlet healthServlet;

  private HttpCaCertServlet caCertServlet;

  private HttpCmpServlet cmpServlet;

  private HttpRestServlet restServlet;

  private HttpScepServlet scepServlet;

  private boolean remoteMgmtEnabled;

  private HttpMgmtServlet mgmgServlet;

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    audits = new Audits();
    try {
      audits.init();
    } catch (IOException ex) {
      throw new ServletException("Exception while initializing Audits", ex);
    }

    securities = new Securities();
    try {
      securities.init();
    } catch (IOException | InvalidConfException ex) {
      throw new ServletException("Exception while initializing Securites", ex);
    }

    caManager = new CaManagerImpl();
    caManager.setAuditServiceRegister(audits.getAuditServiceRegister());
    caManager.setSecurityFactory(securities.getSecurityFactory());

    Properties props = new Properties();
    InputStream is = null;
    try {
      is = Files.newInputStream(Paths.get(DFLT_CA_SERVER_CFG));
      props.load(is);
    } catch (IOException ex) {
      throw new ServletException("could not load properties from file " + DFLT_CA_SERVER_CFG);
    } finally {
      IoUtil.closeQuietly(is);
    }

    // Certprofiles
    caManager.setCertprofileFactoryRegister(
        initCertprofileFactoryRegister(props));

    // Publisher
    CertPublisherFactoryRegisterImpl publiserFactoryRegister =
        new CertPublisherFactoryRegisterImpl();
    publiserFactoryRegister.registFactory(new OcspCertPublisherFactory());
    caManager.setCertPublisherFactoryRegister(publiserFactoryRegister);

    caManager.setCaConfFile(DFLT_CA_SERVER_CFG);

    caManager.startCaSystem();

    this.caCertServlet = new HttpCaCertServlet();
    this.caCertServlet.setResponderManager(caManager);

    this.cmpServlet = new HttpCmpServlet();
    this.cmpServlet.setAuditServiceRegister(audits.getAuditServiceRegister());
    this.cmpServlet.setResponderManager(caManager);

    this.healthServlet = new HealthCheckServlet();
    this.healthServlet.setResponderManager(caManager);

    this.restServlet = new HttpRestServlet();
    this.restServlet.setAuditServiceRegister(audits.getAuditServiceRegister());
    this.restServlet.setResponderManager(caManager);

    this.scepServlet = new HttpScepServlet();
    this.scepServlet.setAuditServiceRegister(audits.getAuditServiceRegister());
    this.scepServlet.setResponderManager(caManager);

    remoteMgmtEnabled =
        Boolean.parseBoolean(props.getProperty("remote.mgmt.enabled", "true"));
    LOG.info("remote managemen is {}", remoteMgmtEnabled ? "enabled" : "disabled");

    if (remoteMgmtEnabled) {
      this.mgmgServlet = new HttpMgmtServlet();
      this.mgmgServlet.setCaManager(caManager);
    }
  }

  @Override
  public void destroy() {
    if (securities != null) {
      securities.close();
    }

    if (caManager != null) {
      caManager.close();
    }

    // audits as last
    if (audits != null) {
      audits.close();
    }
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest & response instanceof HttpServletResponse)) {
      throw new ServletException("Only HTTP request is supported");
    }

    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;

    String path = req.getServletPath();
    if (path.startsWith("/cmp/")) {
      req.setAttribute(ATTR_XIPKI_PATH, path.substring(4)); // 4 = "/cmp".length()
      cmpServlet.service(req, res);
    } else if (path.startsWith("/rest/")) {
      req.setAttribute(ATTR_XIPKI_PATH, path.substring(5)); // 5 = "/rest".length()
      restServlet.service(req, res);
    } else if (path.startsWith("/scep/")) {
      req.setAttribute(ATTR_XIPKI_PATH, path.substring(5)); // 5 = "/scep".length()
      scepServlet.service(req, res);
    } else if (path.startsWith("/health/")) {
      req.setAttribute(ATTR_XIPKI_PATH, path.substring(7)); // 7 = "/health".length()
      healthServlet.service(req, res);
    } else if (path.startsWith("/cacert/")) {
      req.setAttribute(ATTR_XIPKI_PATH, path.substring(7)); // 7 = "/cacert".length()
      caCertServlet.service(req, res);
    } else if (path.startsWith("/mgmt/")) {
      if (remoteMgmtEnabled) {
        req.setAttribute(ATTR_XIPKI_PATH, path.substring(5)); // 5 = "/mgmt".length()
        mgmgServlet.service(req, res);
      } else {
        sendError(res, HttpServletResponse.SC_FORBIDDEN);
      }
    } else {
      sendError(res, HttpServletResponse.SC_NOT_FOUND);
    }
  }

  private static void sendError(HttpServletResponse res, int status) {
    res.setStatus(status);
    res.setContentLength(0);
  }

  private CertprofileFactoryRegister initCertprofileFactoryRegister(Properties props)
      throws ServletException {
    CertprofileFactoryRegisterImpl certprofileFactoryRegister =
        new CertprofileFactoryRegisterImpl();
    certprofileFactoryRegister.registFactory(new CertprofileFactoryImpl());

    // register additional SignerFactories
    String list = props.getProperty("Additional.CertprofileFactories");
    String[] classNames = list == null ? null : list.split(", ");
    if (classNames != null) {
      for (String className : classNames) {
        try {
          Class<?> clazz = Class.forName(className);
          CertprofileFactory factory = (CertprofileFactory) clazz.newInstance();
          certprofileFactoryRegister.registFactory(factory);
        } catch (ClassCastException | ClassNotFoundException | IllegalAccessException
            | InstantiationException ex) {
          LOG.error("error caught while initializing CertprofileFactory "
              + className + ": " + ex.getClass().getName() + ": " + ex.getMessage(), ex);
        }
      }
    }

    return certprofileFactoryRegister;
  }

}
