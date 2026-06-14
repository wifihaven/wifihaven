# Existing app-template inventory (2026-06-14)

Source: `api/resources/app_templates/_index.yml` + each `<slug>.yml`.

| slug          | name           | host-set                                                                              |
| ------------- | -------------- | ------------------------------------------------------------------------------------- |
| youtube       | YouTube        | youtube.com, youtu.be, ytimg.com, googlevideo.com                                     |
| tiktok        | TikTok         | tiktok.com, tiktokcdn.com, tiktokv.com, musical.ly, byteoversea.com                   |
| roblox        | Roblox         | roblox.com, rbxcdn.com, robloxlabs.com                                                |
| discord       | Discord        | discord.com, discordapp.com, discordapp.net, discord.gg, discord.media                |
| minecraft     | Minecraft      | minecraft.net, mojang.com, minecraftservices.com, xboxlive.com                        |
| netflix       | Netflix        | netflix.com, nflxvideo.net, nflximg.net, nflxso.net, nflxext.com                      |
| instagram     | Instagram      | instagram.com, cdninstagram.com, fbcdn.net                                            |
| snapchat      | Snapchat       | snapchat.com, sc-cdn.net, snap-dev.net, snapkit.com                                   |
| whatsapp      | WhatsApp       | whatsapp.com, whatsapp.net                                                            |
| twitch        | Twitch         | twitch.tv, ttvnw.net, jtvnw.net, twitchcdn.net                                        |
| gimkit        | Gimkit         | gimkit.com, gimkitconnect.com                                                         |
| khan-academy  | Khan Academy   | khanacademy.org, kastatic.org, kasandbox.org                                          |
| math-academy  | Math Academy   | mathacademy.com                                                                       |
| lexia         | Lexia          | lexialearning.com, lexiacore5.com, lexiapowerup.com                                   |
| imessage      | iMessage       | ess.apple.com (APNs deliberately excluded)                                            |

Format: YAML per template; manifest at `_index.yml`; loader = `api/src/AppTemplates.scala`.
Tests: `api/test/src/feature/AppTemplatesSpec.scala` (loadAll + seed behavior).
