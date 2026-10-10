// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

/** Sky2M has no Hypixel API key, so profile lookups are unavailable. */
public final class ProfileUtils {
	private ProfileUtils() {}

	public static CompletableFuture<JsonObject> fetchProfileMember(String name) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("Hypixel API lookups are not available in Sky2M"));
	}
}
