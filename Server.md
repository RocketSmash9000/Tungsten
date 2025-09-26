The part of Zync anyone can host. Contains the server data (messages, media, roles, etc). If the server is being hosted, refer to [[#Self-hosting]] for more information.

# Hosting
There are two ways in which to host a Zync server. One is significantly easier than the other.

## Server provider
If you have a server in AWS, Oracle or Google, you can download the server binary and host the server through there. Note that it's possible that you will have to open certain ports.

## Self-hosting
You will need to change your router's settings. To self-host the server, you will need to do port forwarding. Please note that some ISPs have the port forwarding feature paywalled, and as such it won't be possible for you to self-host a Zync server.

You can follow [this guide](https://www.noip.com/support/knowledgebase/general-port-forwarding-guide) by No-IP to learn how to port forward.

## Custom domain
If you have a custom domain, you can use it instead of the server's IP address. You only need for the domain name to redirect to the server.

# Development
The way to connect to a server's API will always be the same: `Insert generic API endpoint here`.

### `status`
If it returns nothing, the server is down.
**Possible returns:**
- `open`: the server is accessible
- `wait`: the server is up, but can't be accessed. Can occur if the server gets too many requests, or if there's no Zync server created.
- `closed`: the server is up, but was closed. No other communications will occur.
- `banned`: the server cannot be accessed, because the user was banned. Trying to use the rest of the API will return nothing.

### `serverinfo`
Will return the following:
```json
{
  "serverID": "server ID",
  "totalUsers": "123",
  "activeUsers": "54",
  "serverImage": "Server image encoded to base64"
}
```

### `getmessage?channel=<channelID>`
Will return all messages sent in the last minute.
Running `getmessage?channel=<channelID>&id=<messageID>` will return at most 10 messages starting from the message ID. 
Messages are ordered from least recent first to most recent last.

```json
{
  "messageID": {
	"content": "Message content here",
    "time": "Here the time it was sent"
  },
  "otherMessageID": {
    "content": "Another message content",
    "time": "Here the time the message sent"
  }
}
```

### `getusers`
Returns the following user data for each user in the server:
```json
{
  "UID": {
    "display": "Display name",
    "color": "#4c11ff",
    "pfp": "Profile picture encoded to base64 here"
  },
  "AnotherUID": {
    "display": "Display name",
    "color": "#00ff00",
    "pfp": "Profile picture again"
  }
}
```
`UID` and `anotherUID` will be replaced by each user's UID. It is recommended to do `getusers?pfp=false` to not fetch every user's profile pictures at once, but to load it with `getuser`.

### `getuser?id=<UID>`
Returns the following for a specific UID:
```json
{
  "uid": "User ID",
  "display": "Display name",
  "username": "username",
  "pfp": "Profile picture encoded to base64 here",
  "bio": "About me text goes here"
}
```
It accepts `getuser?id=<UID>&pfp=true`. It will only return the image for a specific user.

### `getchannels`
Returns a list of all channels, with their categories.
Output will be structured in something like the following:
```json
{
  "categoryID": {
	"categoryName": "name",
	"channelID": {
	  "channelName": "name",
	  "type": "text",
	  "permissions": {
		"read": true,
		"write": true,
		"files": false,
		"manageMessages": false,
		"riskyPing": false
	  }
	},
	"channelID": {
	  "channelName": "name",
	  "type": "announcement",
	  "permissions": {
		"read": true,
		"write": false,
		"files": false,
		"manageMessages": false,
		"riskyPing": false
	  }
	}
  }
}
```
Note that `categoryID` and `channelID` will be different.

### `joinrequest?id=<UID>`
Sends a request to the server with the UID.
The server should send a request to the Central to verify the user. When the Central verifies the account, `join` becomes available. The server will return a status code, depending on the verification result.

### `join`
Sends the UID, display name, username, about me and profile picture to the server. Once [[#`joinrequest?id=<UID>`|joinrequest]] returns `true`, this endpoint becomes available. Returns a status code depending on whether the transaction was successful or not.
