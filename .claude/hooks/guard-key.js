// PreToolUse: a new signing key forces an uninstall on the phone, which wipes its database (see CLAUDE.md)
let s = ""
process.stdin.on("data", d => (s += d)).on("end", () => {
  const input = JSON.parse(s).tool_input || {}
  // Edits are checked by target path only, so docs that mention the key path stay editable
  const text = input.command || input.file_path || ""
  if (/keytool[^"]*-genkey|\.android-keys/i.test(text)) {
    console.error("Blocked: never create or touch the release signing key (~/.android-keys). Ask the user.")
    process.exit(2)
  }
})
