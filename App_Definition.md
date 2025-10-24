## App Definition
The app-specific values are defined in a Google Sheet. Each app has its own row. If you go self-hosted, you will create the sheet yourself and put your apps there. If you go managed, we will put your app in our existing sheet and all you have to do is to provide us with the values to fill in. 

---

### Quick Reference Table

| Field | Description | Example |
|-------|-------------|---------|
| `client_name` | The name of your app. Any value you like, adds clarity to the workflow. | `Instagram` |
| `app_id` | Unique lowercase/underscore identifier. Internal use only. | `instagram` |
| `google_package_name` | App ID from Google Play URL. | `com.instagram.android` |
| `app_store_app_id` | App ID from App Store URL. | `389801252` |
| `app_store_key_id` | Apple Individual Key ID (created in App Store Connect). | `33CITLTXSPOQ` |
| `slack_channel_id` | Slack channel ID from channel URL. | `C066278GOK0` |
| `teams_team_id` | Extracted from `groupId` in Teams channel URL. | `11111111-2222-3333-4444-555555555555` |
| `teams_tenant_id` | Extracted from `tenantId` in Teams channel URL. | `aaaaaaa1-bbbb-cccc-dddd-eeeeeeeeeeee` |
| `teams_channel_id` | Extracted from Teams URL (`/channel/...`), URL-decoded. | `19:abcdEFGHijklMNOpqrSTUvwxYZ1234567890@thread.tacv2` |
| `language` | Language the bot uses when sending messages to channels. Supports only `"en"` or `"cs"`. | `en` |
| `ai_instructions` | Prompt for AI to generate replies. | *(see detailed prompt below)* |
| `launch_date` | Date (`YYYY-MM-DD`) from which reviews are processed. | `2024-05-01` |

---

### Detailed Guides

#### `google_package_name`
Can be obtained from the app’s URL on Google Play Store.  
Example URL:  
`https://play.google.com/store/apps/details?id=com.instagram.android`  
Identifier: `com.instagram.android`

#### `app_store_app_id`
Can be obtained from the app’s URL on App Store.  
Example URL:  
`https://apps.apple.com/us/app/instagram/id389801252`  
Identifier: `389801252`

#### `app_store_key_id`
To fetch and reply to reviews, create an **Individual Key** in App Store Connect.  
Recommended flow:  
1. Add a new account to your Organization.  
2. Assign it the *Customer Support* role.  
3. Check *Generate Individual API Keys* in **Additional Resources**.  
4. Create and download the key → copy the **Key ID**.  

#### `slack_channel_id`
Obtainable from the channel’s link.  
Example URL:  
`https://mateecz.slack.com/archives/C066278GOK0`  
Channel ID: `C066278GOK0`

#### `teams_tenant_id`, `teams_team_id`, `teams_channel_id`
Identifiers used by Microsoft Teams.  

**How to obtain:**  
1. In Microsoft Teams, open the channel → More options (⋯) → Copy Link.  
2. From the generated URL:  
   - `teams_team_id` = the value of `groupId=`  
   - `teams_tenant_id` = the value of `tenantId=`  
   - `teams_channel_id` = the segment after `/channel/` and before the next `/` (URL-decode it).  

**Example URL:**  
`https://teams.microsoft.com/l/channel/19%3AabcdEFGHijklMNOpqrSTUvwxYZ1234567890%40thread.tacv2/General?groupId=11111111-2222-3333-4444-555555555555&tenantId=aaaaaaa1-bbbb-cccc-dddd-eeeeeeeeeeee`

**Extracted values:**  
- `teams_team_id`: `11111111-2222-3333-4444-555555555555`  
- `teams_tenant_id`: `aaaaaaa1-bbbb-cccc-dddd-eeeeeeeeeeee`  
- `teams_channel_id` (decoded): `19:abcdEFGHijklMNOpqrSTUvwxYZ1234567890@thread.tacv2`

#### `language`
Configures which language the bot uses when sending messages into the channels.  
Only two values are supported:  
- `"en"` → English  
- `"cs"` → Czech  

Use this to make sure notifications are understandable to your support team.  

#### `ai_instructions`
The instructions for the AI which generates suggested replies to the reviews.  
You don’t need to add reply length constraints; they are handled automatically.  

**Example prompt:**  
> You are an assistant responding to customer reviews. Replies should be in Czech, friendly but professional.  
> Always greet the customer and thank them for the review.  
> If the review is positive, simply thank them for the kind words.  
> If the review is negative or something is not working, thank them as well and ask them to contact our support at +420 123 456 789 so we can help them resolve it.  
> If the customer complains about speed, design, or other general issues (not bugs), thank them and say that we monitor customer feedback and will react accordingly. For example, with speed issues, mention that we will check it and do our best to improve.  
> If the review is unclear (e.g. “it doesn’t work”), thank them and ask for more details so we can assist.  
> Always sign the reply with: **Instagram Team**

#### `launch_date`
A date in the `YYYY-MM-DD` format.  
Reviews before this date will not be processed (prevents spam from old reviews).  
Reviews are **never sent twice**.
