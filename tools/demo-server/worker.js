// MK IPTV public demo server: a tiny, standard Xtream Codes API (player_api.php) for App Review and new users.
// Login: username "demo", password "demo". Content is public: the Saudi Broadcasting Authority's public
// Al Quran Al Kareem TV live stream, and public-domain NASA videos (https://images.nasa.gov).
// Streams are 302 redirects to their public sources; nothing is hosted or proxied here.

const USER = "demo";
const PASS = "demo";
const NASA = "https://images-assets.nasa.gov/video";
const nasa = (id, file = "medium.mp4") => `${NASA}/${encodeURIComponent(id)}/${encodeURIComponent(id)}~${file}`;
const QURAN_CHUNKS = "http://m.live.net.sa:1935/live/quran/chunklist.m3u8";
const QURAN_LOGO = "";

const LIVE = [
  { num: 1, name: "Al Quran Al Kareem TV (Makkah Live)", stream_id: 1, category_id: "1", stream_icon: QURAN_LOGO, epg_channel_id: "" },
];

const MOVIES = [
  { id: 101, nasa: "jsc2022m000172_Earth_in_4K_Expedition_65_Edition", thumb: "small_1.jpg", name: "Earth from Space in 4K", year: "2022",
    plot: "Views of our planet filmed by astronauts aboard the International Space Station during Expedition 65." },
  { id: 102, nasa: "NHQ_2019_0626_Earth Views from the ISS", thumb: "thumb_2.jpg", name: "Earth Views from the ISS", year: "2019",
    plot: "Sunrises, cities at night and weather systems, seen from the International Space Station." },
  { id: 103, nasa: "JPL-20240109-NISARf-0001-Tracking Earth's Changes from Space", thumb: "small.jpg", name: "NISAR: Tracking Earth's Changes", year: "2024",
    plot: "How the NISAR satellite will measure changes in Earth's land and ice surfaces." },
  { id: 104, nasa: "GSFC_20180927_EPIC_m13056_Products", thumb: "small_1.jpg", name: "EPIC: Earth from a Million Miles", year: "2018",
    plot: "What NASA's EPIC camera sees of the whole sunlit Earth from a million miles away." },
];

const SERIES = [
  { series_id: 201, name: "NASA Science Live", year: "2019",
    plot: "NASA scientists answer questions about the Moon, the planets and the stars.",
    episodes: [
      { id: "301", num: 1, nasa: "GSFC_20190227_M13153_NSL01", title: "To the Moon and Beyond" },
      { id: "302", num: 2, nasa: "GSFC_20190327_M13161_NSL", title: "Going Interstellar" },
      { id: "303", num: 3, nasa: "GSFC_20190522_M13212_NSL", title: "Storms Across the Solar System" },
      { id: "304", num: 4, nasa: "GSFC_20191018_m13344_NSL09", title: "A Telescope Like a Time Machine" },
    ] },
];

const poster = (m) => nasa(m.nasa, m.thumb);
const epThumb = (e) => nasa(e.nasa, "small_1.jpg");
const ADDED = "1763600000";

function json(obj) {
  return new Response(JSON.stringify(obj), { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=300" } });
}

function api(action, q) {
  switch (action) {
    case null:
    case undefined:
      return {
        user_info: { username: USER, auth: 1, status: "Active", exp_date: "4102444800", is_trial: "0", active_cons: "0", max_connections: "1", allowed_output_formats: ["m3u8"] },
        server_info: { url: q.host, port: "443", https_port: "443", server_protocol: "https", timezone: "UTC" },
      };
    case "get_live_categories": return [{ category_id: "1", category_name: "Quran", parent_id: 0 }];
    case "get_live_streams": return LIVE.map((c) => ({ ...c, stream_type: "live", added: ADDED }));
    case "get_vod_categories": return [{ category_id: "10", category_name: "Earth from Space", parent_id: 0 }];
    case "get_vod_streams":
      return MOVIES.map((m) => ({ num: m.id, name: m.name, stream_type: "movie", stream_id: m.id, stream_icon: poster(m), rating: "8", rating_5based: 4,
        added: ADDED, category_id: "10", container_extension: "mp4" }));
    case "get_vod_info": {
      const m = MOVIES.find((x) => String(x.id) === q.get("vod_id"));
      if (!m) return {};
      return { info: { name: m.name, plot: m.plot, genre: "Documentary", releasedate: m.year, movie_image: poster(m), cover_big: poster(m), rating: "8" },
        movie_data: { stream_id: m.id, name: m.name, container_extension: "mp4", category_id: "10" } };
    }
    case "get_series_categories": return [{ category_id: "20", category_name: "Science", parent_id: 0 }];
    case "get_series":
      return SERIES.map((s) => ({ num: s.series_id, name: s.name, series_id: s.series_id, cover: epThumb(s.episodes[0]), plot: s.plot, genre: "Science",
        releaseDate: s.year, rating: "8", rating_5based: 4, last_modified: ADDED, category_id: "20", backdrop_path: [epThumb(s.episodes[0])] }));
    case "get_series_info": {
      const s = SERIES.find((x) => String(x.series_id) === q.get("series_id"));
      if (!s) return {};
      return { seasons: [{ season_number: 1, name: "Season 1" }],
        info: { name: s.name, plot: s.plot, genre: "Science", releaseDate: s.year, rating: "8", cover: epThumb(s.episodes[0]), backdrop_path: [epThumb(s.episodes[0])] },
        episodes: { "1": s.episodes.map((e) => ({ id: e.id, episode_num: e.num, season: 1, title: e.title, container_extension: "mp4",
          info: { movie_image: epThumb(e), plot: `${s.name}, episode ${e.num}.` } })) } };
    }
    case "get_short_epg": return { epg_listings: [] };
    default: return [];
  }
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const p = url.pathname;
    if (p === "/" || p === "") {
      return new Response("MK IPTV demo server (Xtream Codes API). Username: demo, password: demo. Public content only: Al Quran Al Kareem TV live and NASA public-domain videos.\n", { headers: { "content-type": "text/plain" } });
    }
    if (p.endsWith("/player_api.php")) {
      const q = url.searchParams;
      if (q.get("username") !== USER || q.get("password") !== PASS) return json({ user_info: { auth: 0 } });
      q.host = url.host;
      return json(api(q.get("action"), q));
    }
    // Streams: /live|movie|series/demo/demo/<id>.<ext>
    const m = p.match(/^\/(live|movie|series)\/([^/]+)\/([^/]+)\/([^/.]+)\.(\w+)$/);
    if (!m || m[2] !== USER || m[3] !== PASS) return new Response("Not found", { status: 404 });
    const [, kind, , , id, ext] = m;
    if (kind === "live") {
      if (id !== "1" || ext !== "m3u8") return new Response("Not found", { status: 404 });
      const master = `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=901305,CODECS="avc1.4d4020,mp4a.40.2",RESOLUTION=1280x720\n${QURAN_CHUNKS}\n`;
      return new Response(master, { headers: { "content-type": "application/vnd.apple.mpegurl", "cache-control": "no-cache" } });
    }
    if (kind === "movie") {
      const mv = MOVIES.find((x) => String(x.id) === id);
      return mv ? Response.redirect(nasa(mv.nasa), 302) : new Response("Not found", { status: 404 });
    }
    for (const s of SERIES) {
      const e = s.episodes.find((x) => x.id === id);
      if (e) return Response.redirect(nasa(e.nasa), 302);
    }
    return new Response("Not found", { status: 404 });
  },
};
