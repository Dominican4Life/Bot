// @version 1
// Blank anime source (JavaScript): the editor's skeleton with every method empty. Fill each part; the
// comments say what the app expects back (guide 08 §4.3).

/*@part id="headers"*/
// Identity: name, language, the site's address (also the Referer of its pictures) and an icon.
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: "{{baseUrl}}",
  iconUrl: "{{iconUrl}}",
/*@end*/
/*@part id="search"*/
  // Search: a list of entries { url, title, coverUrl } for the words and page.
  search: async function (query, page, filters) {
    return [];
  },
/*@end*/
/*@part id="getPopular"*/
  // Popular: a list of entries for one page.
  getPopular: async function (page) {
    return [];
  },
/*@end*/
/*@part id="getLatest"*/
  // Latest: a list of entries for one page.
  getLatest: async function (page) {
    return [];
  },
/*@end*/
/*@part id="getChapterList"*/
  // Episodes: one chapter each { url, title, number }, with season, seasonName and seasonCoverUrl for seasons.
  getChapterList: async function (entryUrl) {
    return [];
  },
/*@end*/
/*@part id="getVideoList"*/
  // Videos, best first: { url, quality, server, headers: { Referer }, subtitles: [{ url, label, language }] }.
  getVideoList: async function (chapterUrl) {
    return [];
  },
/*@end*/
};
