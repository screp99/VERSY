package aqua.blatt1.common.msgtypes;

import java.io.Serializable;

@SuppressWarnings("serial")
public final class Token implements Serializable {

	private static Token instance;

	private Token() {
	}

	public static synchronized Token getInstance() {
		if (Token.instance == null) {
			Token.instance = new Token();
		}
		return Token.instance;
	}
}
