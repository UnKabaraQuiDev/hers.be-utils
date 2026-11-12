package lu.kbra.webcal_cmp.files;

/** Common storage columns; each source retains its own independent database table. */
public interface FileEntry {

	String getRemoteId();

	int getVersion();

	String getCourseCode();

	String getOriginalName();

	String getRelativePath();

	void setRelativePath(String path);

	String getSha256();

	String getLinkRelativePath();

	void setLinkRelativePath(String path);
}
