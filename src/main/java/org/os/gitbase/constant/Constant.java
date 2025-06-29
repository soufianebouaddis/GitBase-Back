package org.os.gitbase.constant;

public class Constant {
    public static final String ACCESS_TOKEN = "GITBASE-TOKEN";
    public static final String REFRESH_TOKEN = "GITBASE-REFRESH-TOKEN";

    //AUTH ENDPOINT
    public static final String AUTH_MAPPING_REQUEST = "/api/v1/auth";
    public static final String REFRESH_TOKEN_MAPPING_REQUEST = AUTH_MAPPING_REQUEST+"/refreshToken";
}
