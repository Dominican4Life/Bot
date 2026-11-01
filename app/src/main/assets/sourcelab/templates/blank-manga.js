// @version 1
// Blank manga source (JavaScript): the editor's skeleton with every method empty. Fill each part; the
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
  // Chapters: a list of { url, title, number, dateUploaded } for an entry's url.
  getChapterList: async function (entryUrl) {
    return [];
  },
/*@end*/
/*@part id="getPageList"*/
  // Pages: a list of { index, imageUrl } for a chapter's url; addresses must be absolute.
  getPageList: async function (chapterUrl) {
    return [];
  },
/*@end*/
};
