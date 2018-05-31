package org.wso2.carbon.apimgt.keymgt.token;

import com.nimbusds.jwt.JWTClaimsSet;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.KeyManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dto.JwtTokenInfoDTO;
import org.wso2.carbon.apimgt.impl.factory.KeyManagerHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.UUID;

public class APIMJWTGenerator extends JWTGenerator {

    private static final Log log = LogFactory.getLog(APIMJWTGenerator.class);
    private static final String SHA256_WITH_RSA = "SHA256withRSA";
    private String signatureAlgorithm = SHA256_WITH_RSA;

    private static final String NONE = "NONE";

    private String userAttributeSeparator = APIConstants.MULTI_ATTRIBUTE_SEPARATOR_DEFAULT;

    public String generateJWT(JwtTokenInfoDTO jwtTokenInfoDTO) throws APIManagementException {

        String jwtHeader = buildHeader(jwtTokenInfoDTO);

        String base64UrlEncodedHeader = "";
        if (jwtHeader != null) {
            base64UrlEncodedHeader = encode(jwtHeader.getBytes(Charset.defaultCharset()));
        }

        String jwtBody = buildBody(jwtTokenInfoDTO);
        String base64UrlEncodedBody = "";
        if (jwtBody != null) {
            base64UrlEncodedBody = encode(jwtBody.getBytes());
        }

        if (SHA256_WITH_RSA.equals(signatureAlgorithm)) {
            String assertion = base64UrlEncodedHeader + '.' + base64UrlEncodedBody;

            //get the assertion signed
            byte[] signedAssertion = signJWT(assertion, jwtTokenInfoDTO.getEndUserName());

            if (log.isDebugEnabled()) {
                log.debug("signed assertion value : " + new String(signedAssertion, Charset.defaultCharset()));
            }
            String base64UrlEncodedAssertion = encode(signedAssertion);

            return base64UrlEncodedHeader + '.' + base64UrlEncodedBody + '.' + base64UrlEncodedAssertion;
        } else {
            return base64UrlEncodedHeader + '.' + base64UrlEncodedBody + '.';
        }
    }

    public String buildHeader(JwtTokenInfoDTO JwtTokenInfoDTO) throws APIManagementException {
        String jwtHeader = null;

        //if signature algo==NONE, header without cert
        if (NONE.equals(signatureAlgorithm)) {
            StringBuilder jwtHeaderBuilder = new StringBuilder();
            jwtHeaderBuilder.append("{\"typ\":\"JWT\",");
            jwtHeaderBuilder.append("\"alg\":\"");
            jwtHeaderBuilder.append(getJWSCompliantAlgorithmCode(NONE));
            jwtHeaderBuilder.append('\"');
            jwtHeaderBuilder.append('}');

            jwtHeader = jwtHeaderBuilder.toString();

        } else if (SHA256_WITH_RSA.equals(signatureAlgorithm)) {
            jwtHeader = addCertToHeader(JwtTokenInfoDTO.getEndUserName());
        }
        return jwtHeader;
    }

    public String buildBody(JwtTokenInfoDTO jwtTokenInfoDTO) throws APIManagementException {

        Map<String, Object> standardClaims = populateStandardClaims(jwtTokenInfoDTO);

        //get tenantId
        int tenantId = APIUtil.getTenantId(jwtTokenInfoDTO.getEndUserName());

        String claimSeparator = getMultiAttributeSeparator(tenantId);
        if (StringUtils.isNotBlank(claimSeparator)) {
            userAttributeSeparator = claimSeparator;
        }

        if (standardClaims != null) {
            JWTClaimsSet.Builder jwtClaimsSetBuilder = new JWTClaimsSet.Builder();

            Iterator<String> it = new TreeSet(standardClaims.keySet()).iterator();
            while (it.hasNext()) {
                String claimURI = it.next();

                Object claimValObj = standardClaims.get(claimURI);
                if (claimValObj instanceof String) {
                    String claimVal = (String) claimValObj;
                    List<String> claimList = new ArrayList<String>();
                    if (userAttributeSeparator != null && claimVal
                            .contains(userAttributeSeparator)) {
                        StringTokenizer st = new StringTokenizer(claimVal, userAttributeSeparator);
                        while (st.hasMoreElements()) {
                            String attValue = st.nextElement().toString();
                            if (StringUtils.isNotBlank(attValue)) {
                                claimList.add(attValue);
                            }
                        }
                        jwtClaimsSetBuilder.claim(claimURI, claimList.toArray(new String[claimList.size()]));
                    } else if (APIConstants.EXP.equals(claimURI)) {
                        jwtClaimsSetBuilder.claim(APIConstants.EXP, new Date(Long.valueOf((String) standardClaims.get(claimURI))));
                    } else {
                        jwtClaimsSetBuilder.claim(claimURI, claimVal);
                    }
                }
            }

            return jwtClaimsSetBuilder.build().toJSONObject().toJSONString();
        }
        return null;
    }

    public Map<String, Object> populateStandardClaims(JwtTokenInfoDTO jwtTokenInfoDTO) throws APIManagementException {

        //generating expiring timestamp
        long currentTime = System.currentTimeMillis();
        long expireIn = currentTime + getTTL() * 1000;

        String endUserName = jwtTokenInfoDTO.getEndUserName();

        Map<String, Object> claims = new LinkedHashMap<String, Object>(20);


        KeyManagerConfiguration configuration = KeyManagerHolder.getKeyManagerInstance().getKeyManagerConfiguration();
        String serverURL = configuration.getParameter(APIConstants.TOKEN_URL);


        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iss", serverURL);
        claims.put("aud", jwtTokenInfoDTO.getAudience());
        claims.put("exp", String.valueOf(expireIn));
        claims.put("enduser", endUserName);
        claims.put("subscribedAPIs", jwtTokenInfoDTO.getSubscribedApiDTOList());

        return claims;
    }
}