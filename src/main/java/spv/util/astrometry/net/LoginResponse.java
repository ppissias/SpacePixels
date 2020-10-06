package spv.util.astrometry.net;

/**
 * Login Response
 * @author Petros Pissias
 *
 */
public class LoginResponse {
	private String status;
	private String message;	
	private String session;

	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public String getSession() {
		return session;
	}
	public void setSession(String session) {
		this.session = session;
	}
	@Override
	public String toString() {
		return "LoginResponse [status=" + status + ", message=" + message + ", session=" + session + "]";
	}
	
}
