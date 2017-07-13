package net.ripe.db.whois.api.rest.search;

import jdk.nashorn.internal.ir.annotations.Immutable;
import net.ripe.db.whois.common.domain.CIString;

import javax.xml.bind.annotation.XmlRootElement;

@Immutable
@XmlRootElement
public class ResourceHolder {

    private String orgKey;
    private String orgName;

    private ResourceHolder() {
        // required no-arg constructor
    }

    public ResourceHolder(final String orgKey, final String orgName) {
        this.orgKey = orgKey;
        this.orgName = orgName;
    }

    public ResourceHolder(final CIString orgKey, final CIString orgName) {
        this.orgKey = orgKey == null ? null : orgKey.toString();
        this.orgName = orgName == null ? null : orgName.toString();
    }

    public String getOrgKey() {
        return orgKey;
    }

    public String getOrgName() {
        return orgName;
    }
}
